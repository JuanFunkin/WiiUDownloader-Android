package wiiu

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	wiiudownloader "github.com/Xpl0itU/WiiUDownloader"
)

// Listener receives download events. It is implemented on the Kotlin side.
// Callbacks arrive on a Go goroutine, never on the main thread.
type Listener interface {
	// OnTitleStarted fires when a title of the queue begins (index is 0 based).
	OnTitleStarted(index, total int32, tid, name string)
	// OnProgress reports bytes downloaded and the total for the current title.
	OnProgress(downloaded, total, bytesPerSecond int64)
	// OnDecryptProgress reports decryption progress from 0.0 to 1.0.
	OnDecryptProgress(fraction float64)
	// OnTitleFinished fires when a title is complete or failed. It blocks the
	// download loop until it returns, so the app can move the finished files out
	// of staging before the next title starts. On success encDir and outDir are
	// the staged folders to deliver (an empty string means "none").
	OnTitleFinished(tid, name string, success bool, message, encDir, outDir string)
	// OnFinished fires once after the whole queue ended.
	OnFinished(cancelled bool)
}

type queueItem struct {
	TID     string `json:"tid"`
	Version int    `json:"version"`
}

// Downloader runs a queue of titles. Create one per run.
type Downloader struct {
	workDir         string
	decrypt         bool
	deleteEncrypted bool
	continueOnError bool
	listener        Listener
	client          *http.Client
	rep             *reporter

	mu      sync.Mutex
	running bool
}

// NewDownloader prepares a run. Files are staged under workDir; when decrypt
// is true the decrypted result goes to workDir/out and the encrypted files to
// workDir/enc. deleteEncrypted drops the encrypted copy after decrypting.
func NewDownloader(workDir string, decrypt, deleteEncrypted, continueOnError bool, listener Listener) *Downloader {
	d := &Downloader{
		workDir:         workDir,
		decrypt:         decrypt,
		deleteEncrypted: deleteEncrypted,
		continueOnError: continueOnError,
		listener:        listener,
		client:          newHTTPClient(),
	}
	d.rep = newReporter(listener)
	return d
}

// Start launches the queue asynchronously. itemsJSON is an array of
// {"tid":"<16 hex digits>","version":-1}.
func (d *Downloader) Start(itemsJSON string) error {
	var items []queueItem
	if err := json.Unmarshal([]byte(itemsJSON), &items); err != nil {
		return fmt.Errorf("invalid queue: %w", err)
	}
	if len(items) == 0 {
		return errors.New("the queue is empty")
	}
	for _, it := range items {
		if _, err := strconv.ParseUint(strings.TrimSpace(it.TID), 16, 64); err != nil {
			return fmt.Errorf("invalid title id %q", it.TID)
		}
	}

	d.mu.Lock()
	if d.running {
		d.mu.Unlock()
		return errors.New("a download is already running")
	}
	d.running = true
	d.mu.Unlock()

	go d.run(items)
	return nil
}

func (d *Downloader) Pause()         { d.rep.setPaused(true) }
func (d *Downloader) Resume()        { d.rep.setPaused(false) }
func (d *Downloader) Cancel()        { d.rep.SetCancelled() }
func (d *Downloader) IsPaused() bool { return d.rep.isPaused() }

func (d *Downloader) run(items []queueItem) {
	defer func() {
		d.mu.Lock()
		d.running = false
		d.mu.Unlock()
		d.listener.OnFinished(d.rep.Cancelled())
	}()

	encRoot := filepath.Join(d.workDir, "enc")
	outRoot := filepath.Join(d.workDir, "out")
	decryptOut := ""
	if d.decrypt {
		decryptOut = outRoot
	}

	for i, it := range items {
		if d.rep.Cancelled() {
			return
		}

		id, _ := strconv.ParseUint(strings.TrimSpace(it.TID), 16, 64)
		tidStr := fmt.Sprintf("%016x", id)
		entry := wiiudownloader.GetTitleEntryFromTid(id)
		name := entry.Name
		if name == "" {
			name = tidStr
		}

		folder := fmt.Sprintf("%s [%s] [%s]", normalizeFilename(name), wiiudownloader.GetFormattedKind(id), tidStr)
		if it.Version >= 0 {
			folder = fmt.Sprintf("%s [v%d]", folder, it.Version)
		}
		encDir := filepath.Join(encRoot, folder)

		d.listener.OnTitleStarted(int32(i), int32(len(items)), tidStr, name)

		err := wiiudownloader.DownloadTitle(tidStr, encDir, it.Version, d.decrypt, d.rep, d.deleteEncrypted, d.client, decryptOut)

		if d.rep.Cancelled() {
			// Keep the encrypted staging folder so the download can resume later;
			// a half decrypted output is useless.
			if d.decrypt {
				_ = os.RemoveAll(filepath.Join(outRoot, folder))
			}
			return
		}

		if err != nil {
			d.listener.OnTitleFinished(tidStr, name, false, err.Error(), "", "")
			if !d.continueOnError {
				return
			}
			continue
		}

		deliverEnc, deliverOut := "", ""
		if d.decrypt {
			if p := filepath.Join(outRoot, folder); dirExists(p) {
				deliverOut = p
			}
			if !d.deleteEncrypted && dirExists(encDir) {
				deliverEnc = encDir
			}
		} else if dirExists(encDir) {
			deliverEnc = encDir
		}
		d.listener.OnTitleFinished(tidStr, name, true, "", deliverEnc, deliverOut)
	}
}

func dirExists(path string) bool {
	st, err := os.Stat(path)
	return err == nil && st.IsDir()
}

// normalizeFilename keeps letters, digits, single spaces and single underscores,
// matching the folder naming of the desktop app so results are interchangeable.
func normalizeFilename(filename string) string {
	var out strings.Builder
	shouldAppend := true
	firstChar := true

	for _, c := range filename {
		switch {
		case c == '_':
			if shouldAppend {
				out.WriteRune('_')
				shouldAppend = false
			}
			firstChar = false
		case c == ' ':
			if shouldAppend && !firstChar {
				out.WriteRune(' ')
				shouldAppend = false
			}
		case (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9'):
			out.WriteRune(c)
			shouldAppend = true
			firstChar = false
		}
	}

	result := strings.TrimSpace(out.String())
	if result == "" {
		return "title"
	}
	return result
}

// reporter implements wiiudownloader.ProgressReporter and the optional
// WaitIfPaused hook, forwarding progress to the Listener.
type reporter struct {
	listener Listener

	ctrlMu    sync.Mutex
	ctrlCond  *sync.Cond
	cancelled bool
	paused    bool
	cancelCh  chan struct{}
	cancelOne sync.Once

	progMu          sync.Mutex
	perFile         map[string]int64
	totalDownloaded int64
	totalToDownload int64
	lastEmit        time.Time
	lastTotal       int64
	speed           float64

	decMu       sync.Mutex
	lastDecEmit time.Time
}

func newReporter(l Listener) *reporter {
	r := &reporter{
		listener: l,
		perFile:  make(map[string]int64),
		cancelCh: make(chan struct{}),
	}
	r.ctrlCond = sync.NewCond(&r.ctrlMu)
	return r
}

func (r *reporter) SetGameTitle(string) {}

func (r *reporter) UpdateDownloadProgress(downloaded int64, filename string) {
	if downloaded == 0 {
		return
	}
	r.progMu.Lock()
	if _, ok := r.perFile[filename]; !ok {
		r.progMu.Unlock()
		return
	}
	r.perFile[filename] += downloaded

	now := time.Now()
	if !r.lastEmit.IsZero() && now.Sub(r.lastEmit) < 250*time.Millisecond {
		r.progMu.Unlock()
		return
	}
	total := r.totalDownloaded
	for _, v := range r.perFile {
		total += v
	}
	if !r.lastEmit.IsZero() {
		dt := now.Sub(r.lastEmit).Seconds()
		if dt > 0 {
			inst := float64(total-r.lastTotal) / dt
			if r.speed == 0 {
				r.speed = inst
			} else {
				r.speed = 0.7*r.speed + 0.3*inst
			}
		}
	}
	r.lastEmit = now
	r.lastTotal = total
	toDownload := r.totalToDownload
	speed := int64(r.speed)
	r.progMu.Unlock()

	r.listener.OnProgress(total, toDownload, speed)
}

func (r *reporter) UpdateDecryptionProgress(progress float64) {
	r.decMu.Lock()
	now := time.Now()
	if progress < 1 && now.Sub(r.lastDecEmit) < 150*time.Millisecond {
		r.decMu.Unlock()
		return
	}
	r.lastDecEmit = now
	r.decMu.Unlock()
	r.listener.OnDecryptProgress(progress)
}

func (r *reporter) Cancelled() bool {
	r.ctrlMu.Lock()
	defer r.ctrlMu.Unlock()
	return r.cancelled
}

func (r *reporter) SetCancelled() {
	r.ctrlMu.Lock()
	r.cancelled = true
	r.paused = false
	r.ctrlCond.Broadcast()
	r.ctrlMu.Unlock()
	r.cancelOne.Do(func() { close(r.cancelCh) })
}

func (r *reporter) Done() <-chan struct{} { return r.cancelCh }

func (r *reporter) WaitIfPaused() bool {
	r.ctrlMu.Lock()
	defer r.ctrlMu.Unlock()
	for r.paused && !r.cancelled {
		r.ctrlCond.Wait()
	}
	return !r.cancelled
}

func (r *reporter) setPaused(p bool) {
	r.ctrlMu.Lock()
	if !r.cancelled {
		r.paused = p
		if !p {
			r.ctrlCond.Broadcast()
		}
	}
	r.ctrlMu.Unlock()
}

func (r *reporter) isPaused() bool {
	r.ctrlMu.Lock()
	defer r.ctrlMu.Unlock()
	return r.paused
}

func (r *reporter) SetDownloadSize(size int64) {
	r.progMu.Lock()
	r.totalToDownload = size
	r.progMu.Unlock()
}

// ResetTotals starts a new title. Cancel and pause state deliberately survive:
// they belong to the whole run.
func (r *reporter) ResetTotals() {
	r.progMu.Lock()
	r.perFile = make(map[string]int64)
	r.totalDownloaded = 0
	r.totalToDownload = 0
	r.lastEmit = time.Time{}
	r.lastTotal = 0
	r.speed = 0
	r.progMu.Unlock()
}

func (r *reporter) MarkFileAsDone(filename string) {
	r.progMu.Lock()
	r.totalDownloaded += r.perFile[filename]
	delete(r.perFile, filename)
	r.progMu.Unlock()
}

func (r *reporter) SetTotalDownloadedForFile(filename string, downloaded int64) {
	r.progMu.Lock()
	r.perFile[filename] = downloaded
	r.progMu.Unlock()
}

func (r *reporter) SetStartTime(time.Time) {}
