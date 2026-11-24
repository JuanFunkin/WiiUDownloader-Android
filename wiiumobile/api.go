// Package wiiu is the gomobile-friendly surface of WiiUDownloader. It exposes
// only simple types (strings, ints, bools, interfaces) so that `gomobile bind`
// can generate Java/Kotlin bindings. Lists travel as JSON strings.
package wiiu

import (
	"encoding/json"
	"fmt"
	"os"
	"sort"
	"strconv"
	"strings"

	wiiudownloader "github.com/Xpl0itU/WiiUDownloader"
)

// VersionLatest is the version value that selects the newest TMD.
const VersionLatest = -1

type titleJSON struct {
	TID        string `json:"tid"`
	Name       string `json:"name"`
	Kind       string `json:"kind"`
	Region     string `json:"region"`
	RegionMask int    `json:"regionMask"`
}

func toTitleJSON(e wiiudownloader.TitleEntry) titleJSON {
	name := e.Name
	if name == "" {
		name = fmt.Sprintf("%016x", e.TitleID)
	}
	return titleJSON{
		TID:        fmt.Sprintf("%016x", e.TitleID),
		Name:       name,
		Kind:       wiiudownloader.GetFormattedKind(e.TitleID),
		Region:     wiiudownloader.GetFormattedRegion(e.Region),
		RegionMask: int(e.Region),
	}
}

// Init must be called once before anything else. Go on Android has no usable
// default temp directory, so point it at the app's cache directory.
func Init(cacheDir string) {
	if cacheDir == "" {
		return
	}
	_ = os.MkdirAll(cacheDir, 0o755)
	_ = os.Setenv("TMPDIR", cacheDir)
	_ = os.Setenv("HOME", cacheDir)
}

// DatabaseSize returns how many titles the embedded database holds.
func DatabaseSize() int32 {
	return int32(len(wiiudownloader.GetTitleEntries(wiiudownloader.TITLE_CATEGORY_ALL)))
}

func categoryFromString(category string) uint8 {
	switch strings.ToLower(category) {
	case "game":
		return wiiudownloader.TITLE_CATEGORY_GAME
	case "update":
		return wiiudownloader.TITLE_CATEGORY_UPDATE
	case "dlc":
		return wiiudownloader.TITLE_CATEGORY_DLC
	case "demo":
		return wiiudownloader.TITLE_CATEGORY_DEMO
	default:
		return wiiudownloader.TITLE_CATEGORY_ALL
	}
}

// SearchTitles filters the database. Every whitespace separated token of query
// must appear in the title name or in its hexadecimal Title ID. category is one
// of "all", "game", "update", "dlc", "demo". regionMask is a bit mask of
// 1=Japan, 2=USA, 4=Europe (0 means any). The result is a JSON array.
func SearchTitles(query, category string, regionMask, limit int32) string {
	tokens := strings.Fields(strings.ToLower(query))
	entries := wiiudownloader.GetTitleEntries(categoryFromString(category))

	type scored struct {
		entry wiiudownloader.TitleEntry
		score int
		lower string
	}
	matches := make([]scored, 0, 256)
	lowerQuery := strings.ToLower(strings.TrimSpace(query))

	for _, e := range entries {
		if regionMask != 0 && int32(e.Region)&regionMask == 0 {
			continue
		}
		lowerName := strings.ToLower(e.Name)
		haystack := lowerName + " " + fmt.Sprintf("%016x", e.TitleID)
		ok := true
		for _, t := range tokens {
			if !strings.Contains(haystack, t) {
				ok = false
				break
			}
		}
		if !ok {
			continue
		}
		score := 1
		if lowerQuery != "" && strings.HasPrefix(lowerName, lowerQuery) {
			score = 0
		}
		matches = append(matches, scored{entry: e, score: score, lower: lowerName})
	}

	sort.SliceStable(matches, func(i, j int) bool {
		if matches[i].score != matches[j].score {
			return matches[i].score < matches[j].score
		}
		return matches[i].lower < matches[j].lower
	})

	if limit > 0 && len(matches) > int(limit) {
		matches = matches[:limit]
	}

	out := make([]titleJSON, 0, len(matches))
	for _, m := range matches {
		out = append(out, toTitleJSON(m.entry))
	}
	data, err := json.Marshal(out)
	if err != nil {
		return "[]"
	}
	return string(data)
}

// RelatedTitles looks up the update and DLC that belong to a game. The result
// is a JSON object {"update": {...}|null, "dlc": {...}|null}.
func RelatedTitles(tid string) string {
	result := struct {
		Update *titleJSON `json:"update"`
		DLC    *titleJSON `json:"dlc"`
	}{}

	id, err := strconv.ParseUint(strings.TrimSpace(tid), 16, 64)
	if err == nil {
		source := wiiudownloader.GetTitleEntryFromTid(id)
		if source.TitleID != 0 && wiiudownloader.GetTitleIDHigh(source.TitleID) == wiiudownloader.TID_HIGH_GAME {
			if upd, ok := wiiudownloader.FindRelatedTitleByHighAndLow(source, wiiudownloader.TID_HIGH_UPDATE, nil); ok {
				j := toTitleJSON(upd)
				result.Update = &j
			}
			if dlc, ok := wiiudownloader.FindRelatedTitleByHighAndLow(source, wiiudownloader.TID_HIGH_DLC, nil); ok {
				j := toTitleJSON(dlc)
				result.DLC = &j
			}
		}
	}

	data, err := json.Marshal(result)
	if err != nil {
		return "{}"
	}
	return string(data)
}

// TitleSize returns the total download size in bytes of a title. version -1
// means the latest version.
func TitleSize(tid string, version int32) (int64, error) {
	id, err := strconv.ParseUint(strings.TrimSpace(tid), 16, 64)
	if err != nil {
		return 0, err
	}
	size, err := wiiudownloader.FetchTMDSize(id, int(version), newHTTPClient())
	if err != nil {
		return 0, err
	}
	return int64(size), nil
}
