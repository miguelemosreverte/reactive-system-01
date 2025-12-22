// Package usage tracks CLI command usage in SQLite for popularity ranking
package usage

import (
	"database/sql"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"

	_ "github.com/mattn/go-sqlite3"
)

// Tracker tracks command usage in SQLite
type Tracker struct {
	db     *sql.DB
	dbPath string
}

// CommandStats holds usage statistics for a command
type CommandStats struct {
	Command    string
	Count      int
	LastUsed   time.Time
	AvgDuration float64
	SuccessRate float64
}

// New creates a new usage tracker
func New() (*Tracker, error) {
	// Find CLI directory
	dbPath := findDBPath()
	if dbPath == "" {
		return nil, fmt.Errorf("cannot find CLI directory")
	}

	db, err := sql.Open("sqlite3", dbPath)
	if err != nil {
		return nil, err
	}

	t := &Tracker{db: db, dbPath: dbPath}
	if err := t.init(); err != nil {
		db.Close()
		return nil, err
	}

	return t, nil
}

func findDBPath() string {
	// Try current directory first
	if _, err := os.Stat("go.mod"); err == nil {
		return ".usage.db"
	}

	// Try to find cli directory
	dir, _ := os.Getwd()
	for {
		cliDir := filepath.Join(dir, "cli")
		if _, err := os.Stat(filepath.Join(cliDir, "go.mod")); err == nil {
			return filepath.Join(cliDir, ".usage.db")
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			break
		}
		dir = parent
	}

	return ".usage.db"
}

func (t *Tracker) init() error {
	_, err := t.db.Exec(`
		CREATE TABLE IF NOT EXISTS command_usage (
			id INTEGER PRIMARY KEY AUTOINCREMENT,
			command TEXT NOT NULL,
			args TEXT,
			duration_ms INTEGER,
			success INTEGER DEFAULT 1,
			timestamp DATETIME DEFAULT CURRENT_TIMESTAMP
		);
		CREATE INDEX IF NOT EXISTS idx_command ON command_usage(command);
		CREATE INDEX IF NOT EXISTS idx_timestamp ON command_usage(timestamp);
	`)
	return err
}

// Record records a command execution
func (t *Tracker) Record(command string, args []string, duration time.Duration, success bool) error {
	successInt := 0
	if success {
		successInt = 1
	}

	_, err := t.db.Exec(`
		INSERT INTO command_usage (command, args, duration_ms, success)
		VALUES (?, ?, ?, ?)
	`, command, strings.Join(args, " "), duration.Milliseconds(), successInt)

	return err
}

// GetStats returns usage statistics for all commands
func (t *Tracker) GetStats() ([]CommandStats, error) {
	rows, err := t.db.Query(`
		SELECT
			command,
			COUNT(*) as count,
			MAX(timestamp) as last_used,
			AVG(duration_ms) as avg_duration,
			AVG(success) * 100 as success_rate
		FROM command_usage
		GROUP BY command
		ORDER BY count DESC
	`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var stats []CommandStats
	for rows.Next() {
		var s CommandStats
		var lastUsed string
		if err := rows.Scan(&s.Command, &s.Count, &lastUsed, &s.AvgDuration, &s.SuccessRate); err != nil {
			continue
		}
		s.LastUsed, _ = time.Parse("2006-01-02 15:04:05", lastUsed)
		stats = append(stats, s)
	}

	return stats, nil
}

// GetTopCommands returns the top N most used commands
func (t *Tracker) GetTopCommands(n int) ([]string, error) {
	stats, err := t.GetStats()
	if err != nil {
		return nil, err
	}

	var commands []string
	for i, s := range stats {
		if i >= n {
			break
		}
		commands = append(commands, s.Command)
	}

	return commands, nil
}

// GenerateReadme generates markdown with usage rankings
func (t *Tracker) GenerateReadme() (string, error) {
	stats, err := t.GetStats()
	if err != nil {
		return "", err
	}

	var sb strings.Builder
	sb.WriteString("# CLI Command Usage\n\n")
	sb.WriteString("Commands ranked by popularity (local usage).\n\n")
	sb.WriteString("| Rank | Command | Uses | Success Rate | Avg Duration |\n")
	sb.WriteString("|------|---------|------|--------------|-------------|\n")

	for i, s := range stats {
		sb.WriteString(fmt.Sprintf("| %d | `%s` | %d | %.0f%% | %.0fms |\n",
			i+1, s.Command, s.Count, s.SuccessRate, s.AvgDuration))
	}

	sb.WriteString("\n---\n")
	sb.WriteString(fmt.Sprintf("*Generated: %s*\n", time.Now().Format("2006-01-02 15:04:05")))

	return sb.String(), nil
}

// Close closes the database connection
func (t *Tracker) Close() error {
	return t.db.Close()
}

// DefaultRanking returns default command ranking when no usage data
func DefaultRanking() []string {
	return []string{
		"start",
		"status",
		"doctor",
		"bench",
		"rebuild",
		"logs",
		"stop",
		"diagnose",
		"trace",
		"stats",
	}
}

// MergeRankings merges usage-based ranking with defaults
func MergeRankings(usage []string, defaults []string) []string {
	seen := make(map[string]bool)
	var result []string

	// Add usage-based first
	for _, cmd := range usage {
		if !seen[cmd] {
			result = append(result, cmd)
			seen[cmd] = true
		}
	}

	// Add defaults for commands not yet used
	for _, cmd := range defaults {
		if !seen[cmd] {
			result = append(result, cmd)
			seen[cmd] = true
		}
	}

	return result
}

// SortCommandsByPopularity sorts commands by usage count
func SortCommandsByPopularity(commands []string, stats []CommandStats) []string {
	countMap := make(map[string]int)
	for _, s := range stats {
		countMap[s.Command] = s.Count
	}

	sorted := make([]string, len(commands))
	copy(sorted, commands)

	sort.Slice(sorted, func(i, j int) bool {
		return countMap[sorted[i]] > countMap[sorted[j]]
	})

	return sorted
}
