package diagnostic

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"

	_ "github.com/mattn/go-sqlite3"
)

// Storage handles SQLite persistence for benchmark history
type Storage struct {
	db *sql.DB
}

// BenchmarkRun represents a single benchmark execution
type BenchmarkRun struct {
	ID              int64     `json:"id"`
	RunID           string    `json:"run_id"`
	StartTime       time.Time `json:"start_time"`
	EndTime         time.Time `json:"end_time"`
	DurationSeconds int       `json:"duration_seconds"`
	BenchmarkType   string    `json:"benchmark_type"` // "full", "http", "kafka", etc.
	GitCommit       string    `json:"git_commit"`
	GitBranch       string    `json:"git_branch"`
	GitDirty        bool      `json:"git_dirty"`
	ColimaMemoryGB  int       `json:"colima_memory_gb"`
	ColimaCPUs      int       `json:"colima_cpus"`
	Success         bool      `json:"success"`
	ErrorMessage    string    `json:"error_message,omitempty"`
}

// BenchmarkMetrics represents the core metrics from a benchmark run
type BenchmarkMetrics struct {
	ID                  int64   `json:"id"`
	RunID               string  `json:"run_id"`
	TotalRequests       int64   `json:"total_requests"`
	SuccessfulRequests  int64   `json:"successful_requests"`
	FailedRequests      int64   `json:"failed_requests"`
	ThroughputOpsPerSec float64 `json:"throughput_ops_per_sec"`
	LatencyP50Ms        float64 `json:"latency_p50_ms"`
	LatencyP95Ms        float64 `json:"latency_p95_ms"`
	LatencyP99Ms        float64 `json:"latency_p99_ms"`
	LatencyMaxMs        float64 `json:"latency_max_ms"`
	LatencyAvgMs        float64 `json:"latency_avg_ms"`
	ErrorRate           float64 `json:"error_rate"`
}

// ComponentSnapshot represents diagnostic state for a component during benchmark
type ComponentSnapshot struct {
	ID                  int64   `json:"id"`
	RunID               string  `json:"run_id"`
	Component           string  `json:"component"`
	TimestampMs         int64   `json:"timestamp_ms"`
	MemoryUsedMB        float64 `json:"memory_used_mb"`
	MemoryLimitMB       float64 `json:"memory_limit_mb"`
	MemoryPeakMB        float64 `json:"memory_peak_mb"`
	MemoryPercent       float64 `json:"memory_percent"`
	HeapUsedMB          float64 `json:"heap_used_mb"`
	HeapMaxMB           float64 `json:"heap_max_mb"`
	HeapPercent         float64 `json:"heap_percent"`
	CPUPercent          float64 `json:"cpu_percent"`
	ThreadsActive       int     `json:"threads_active"`
	ThreadsMax          int     `json:"threads_max"`
	GCCount             int64   `json:"gc_count"`
	GCTimeMs            int64   `json:"gc_time_ms"`
	Status              string  `json:"status"` // healthy, warning, critical, crashed
	OOMRisk             bool    `json:"oom_risk"`
	DiagnosticJSON      string  `json:"diagnostic_json"` // Full DiagnosticSnapshot as JSON
}

// DefaultStoragePath returns the default path for the benchmark database
func DefaultStoragePath() string {
	home, err := os.UserHomeDir()
	if err != nil {
		return ".reactive-benchmarks.db"
	}
	return filepath.Join(home, ".reactive-benchmarks.db")
}

// ProjectStoragePath returns a project-local path for benchmark database
func ProjectStoragePath() string {
	return ".reactive/benchmarks.db"
}

// NewStorage creates a new Storage instance with the given database path
func NewStorage(dbPath string) (*Storage, error) {
	// Ensure directory exists
	dir := filepath.Dir(dbPath)
	if dir != "." && dir != "" {
		if err := os.MkdirAll(dir, 0755); err != nil {
			return nil, fmt.Errorf("failed to create directory: %w", err)
		}
	}

	db, err := sql.Open("sqlite3", dbPath)
	if err != nil {
		return nil, fmt.Errorf("failed to open database: %w", err)
	}

	s := &Storage{db: db}
	if err := s.initSchema(); err != nil {
		db.Close()
		return nil, fmt.Errorf("failed to initialize schema: %w", err)
	}

	return s, nil
}

// initSchema creates the database tables if they don't exist
func (s *Storage) initSchema() error {
	schema := `
	-- Benchmark runs table
	CREATE TABLE IF NOT EXISTS benchmark_runs (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		run_id TEXT UNIQUE NOT NULL,
		start_time DATETIME NOT NULL,
		end_time DATETIME,
		duration_seconds INTEGER,
		benchmark_type TEXT NOT NULL,
		git_commit TEXT,
		git_branch TEXT,
		git_dirty BOOLEAN DEFAULT 0,
		colima_memory_gb INTEGER,
		colima_cpus INTEGER,
		success BOOLEAN DEFAULT 0,
		error_message TEXT,
		created_at DATETIME DEFAULT CURRENT_TIMESTAMP
	);

	-- Benchmark metrics table
	CREATE TABLE IF NOT EXISTS benchmark_metrics (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		run_id TEXT NOT NULL,
		total_requests INTEGER,
		successful_requests INTEGER,
		failed_requests INTEGER,
		throughput_ops_per_sec REAL,
		latency_p50_ms REAL,
		latency_p95_ms REAL,
		latency_p99_ms REAL,
		latency_max_ms REAL,
		latency_avg_ms REAL,
		error_rate REAL,
		created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
		FOREIGN KEY (run_id) REFERENCES benchmark_runs(run_id)
	);

	-- Component snapshots during benchmarks
	CREATE TABLE IF NOT EXISTS component_snapshots (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		run_id TEXT NOT NULL,
		component TEXT NOT NULL,
		timestamp_ms INTEGER NOT NULL,
		memory_used_mb REAL,
		memory_limit_mb REAL,
		memory_peak_mb REAL,
		memory_percent REAL,
		heap_used_mb REAL,
		heap_max_mb REAL,
		heap_percent REAL,
		cpu_percent REAL,
		threads_active INTEGER,
		threads_max INTEGER,
		gc_count INTEGER,
		gc_time_ms INTEGER,
		status TEXT,
		oom_risk BOOLEAN DEFAULT 0,
		diagnostic_json TEXT,
		created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
		FOREIGN KEY (run_id) REFERENCES benchmark_runs(run_id)
	);

	-- Indexes for common queries
	CREATE INDEX IF NOT EXISTS idx_runs_start_time ON benchmark_runs(start_time);
	CREATE INDEX IF NOT EXISTS idx_runs_git_commit ON benchmark_runs(git_commit);
	CREATE INDEX IF NOT EXISTS idx_runs_benchmark_type ON benchmark_runs(benchmark_type);
	CREATE INDEX IF NOT EXISTS idx_metrics_run_id ON benchmark_metrics(run_id);
	CREATE INDEX IF NOT EXISTS idx_snapshots_run_id ON component_snapshots(run_id);
	CREATE INDEX IF NOT EXISTS idx_snapshots_component ON component_snapshots(component);
	`

	_, err := s.db.Exec(schema)
	return err
}

// Close closes the database connection
func (s *Storage) Close() error {
	return s.db.Close()
}

// GetGitInfo retrieves current git commit and branch
func GetGitInfo() (commit, branch string, dirty bool) {
	// Get commit hash
	cmd := exec.Command("git", "rev-parse", "--short", "HEAD")
	if out, err := cmd.Output(); err == nil {
		commit = strings.TrimSpace(string(out))
	}

	// Get branch name
	cmd = exec.Command("git", "rev-parse", "--abbrev-ref", "HEAD")
	if out, err := cmd.Output(); err == nil {
		branch = strings.TrimSpace(string(out))
	}

	// Check if dirty
	cmd = exec.Command("git", "status", "--porcelain")
	if out, err := cmd.Output(); err == nil {
		dirty = len(strings.TrimSpace(string(out))) > 0
	}

	return
}

// GetColimaInfo retrieves Colima configuration
func GetColimaInfo() (memoryGB, cpus int) {
	cmd := exec.Command("colima", "list", "--json")
	out, err := cmd.Output()
	if err != nil {
		return 0, 0
	}

	var result []struct {
		CPU    int `json:"cpu"`
		Memory int `json:"memory"`
	}
	if err := json.Unmarshal(out, &result); err != nil || len(result) == 0 {
		return 0, 0
	}

	return result[0].Memory / 1024, result[0].CPU
}

// StartRun creates a new benchmark run record
func (s *Storage) StartRun(runID, benchmarkType string, durationSec int) (*BenchmarkRun, error) {
	commit, branch, dirty := GetGitInfo()
	memGB, cpus := GetColimaInfo()

	run := &BenchmarkRun{
		RunID:           runID,
		StartTime:       time.Now(),
		DurationSeconds: durationSec,
		BenchmarkType:   benchmarkType,
		GitCommit:       commit,
		GitBranch:       branch,
		GitDirty:        dirty,
		ColimaMemoryGB:  memGB,
		ColimaCPUs:      cpus,
	}

	result, err := s.db.Exec(`
		INSERT INTO benchmark_runs (run_id, start_time, duration_seconds, benchmark_type,
			git_commit, git_branch, git_dirty, colima_memory_gb, colima_cpus)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		run.RunID, run.StartTime, run.DurationSeconds, run.BenchmarkType,
		run.GitCommit, run.GitBranch, run.GitDirty, run.ColimaMemoryGB, run.ColimaCPUs)
	if err != nil {
		return nil, err
	}

	run.ID, _ = result.LastInsertId()
	return run, nil
}

// EndRun marks a benchmark run as complete
func (s *Storage) EndRun(runID string, success bool, errorMsg string) error {
	_, err := s.db.Exec(`
		UPDATE benchmark_runs
		SET end_time = ?, success = ?, error_message = ?
		WHERE run_id = ?`,
		time.Now(), success, errorMsg, runID)
	return err
}

// SaveMetrics saves benchmark metrics for a run
func (s *Storage) SaveMetrics(m *BenchmarkMetrics) error {
	result, err := s.db.Exec(`
		INSERT INTO benchmark_metrics (run_id, total_requests, successful_requests, failed_requests,
			throughput_ops_per_sec, latency_p50_ms, latency_p95_ms, latency_p99_ms,
			latency_max_ms, latency_avg_ms, error_rate)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		m.RunID, m.TotalRequests, m.SuccessfulRequests, m.FailedRequests,
		m.ThroughputOpsPerSec, m.LatencyP50Ms, m.LatencyP95Ms, m.LatencyP99Ms,
		m.LatencyMaxMs, m.LatencyAvgMs, m.ErrorRate)
	if err != nil {
		return err
	}
	m.ID, _ = result.LastInsertId()
	return nil
}

// SaveComponentSnapshot saves a component diagnostic snapshot
func (s *Storage) SaveComponentSnapshot(snap *ComponentSnapshot) error {
	result, err := s.db.Exec(`
		INSERT INTO component_snapshots (run_id, component, timestamp_ms, memory_used_mb,
			memory_limit_mb, memory_peak_mb, memory_percent, heap_used_mb, heap_max_mb,
			heap_percent, cpu_percent, threads_active, threads_max, gc_count, gc_time_ms,
			status, oom_risk, diagnostic_json)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		snap.RunID, snap.Component, snap.TimestampMs, snap.MemoryUsedMB,
		snap.MemoryLimitMB, snap.MemoryPeakMB, snap.MemoryPercent, snap.HeapUsedMB,
		snap.HeapMaxMB, snap.HeapPercent, snap.CPUPercent, snap.ThreadsActive,
		snap.ThreadsMax, snap.GCCount, snap.GCTimeMs, snap.Status, snap.OOMRisk,
		snap.DiagnosticJSON)
	if err != nil {
		return err
	}
	snap.ID, _ = result.LastInsertId()
	return nil
}

// GetRecentRuns retrieves the most recent benchmark runs
func (s *Storage) GetRecentRuns(limit int) ([]BenchmarkRun, error) {
	rows, err := s.db.Query(`
		SELECT id, run_id, start_time, end_time, duration_seconds, benchmark_type,
			git_commit, git_branch, git_dirty, colima_memory_gb, colima_cpus, success, error_message
		FROM benchmark_runs
		ORDER BY start_time DESC
		LIMIT ?`, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var runs []BenchmarkRun
	for rows.Next() {
		var r BenchmarkRun
		var endTime sql.NullTime
		var errorMsg sql.NullString
		err := rows.Scan(&r.ID, &r.RunID, &r.StartTime, &endTime, &r.DurationSeconds,
			&r.BenchmarkType, &r.GitCommit, &r.GitBranch, &r.GitDirty,
			&r.ColimaMemoryGB, &r.ColimaCPUs, &r.Success, &errorMsg)
		if err != nil {
			return nil, err
		}
		if endTime.Valid {
			r.EndTime = endTime.Time
		}
		if errorMsg.Valid {
			r.ErrorMessage = errorMsg.String
		}
		runs = append(runs, r)
	}
	return runs, nil
}

// GetRunsByCommit retrieves all runs for a specific git commit
func (s *Storage) GetRunsByCommit(commit string) ([]BenchmarkRun, error) {
	rows, err := s.db.Query(`
		SELECT id, run_id, start_time, end_time, duration_seconds, benchmark_type,
			git_commit, git_branch, git_dirty, colima_memory_gb, colima_cpus, success, error_message
		FROM benchmark_runs
		WHERE git_commit = ?
		ORDER BY start_time DESC`, commit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var runs []BenchmarkRun
	for rows.Next() {
		var r BenchmarkRun
		var endTime sql.NullTime
		var errorMsg sql.NullString
		err := rows.Scan(&r.ID, &r.RunID, &r.StartTime, &endTime, &r.DurationSeconds,
			&r.BenchmarkType, &r.GitCommit, &r.GitBranch, &r.GitDirty,
			&r.ColimaMemoryGB, &r.ColimaCPUs, &r.Success, &errorMsg)
		if err != nil {
			return nil, err
		}
		if endTime.Valid {
			r.EndTime = endTime.Time
		}
		if errorMsg.Valid {
			r.ErrorMessage = errorMsg.String
		}
		runs = append(runs, r)
	}
	return runs, nil
}

// GetMetricsForRun retrieves metrics for a specific run
func (s *Storage) GetMetricsForRun(runID string) (*BenchmarkMetrics, error) {
	var m BenchmarkMetrics
	err := s.db.QueryRow(`
		SELECT id, run_id, total_requests, successful_requests, failed_requests,
			throughput_ops_per_sec, latency_p50_ms, latency_p95_ms, latency_p99_ms,
			latency_max_ms, latency_avg_ms, error_rate
		FROM benchmark_metrics
		WHERE run_id = ?`, runID).Scan(
		&m.ID, &m.RunID, &m.TotalRequests, &m.SuccessfulRequests, &m.FailedRequests,
		&m.ThroughputOpsPerSec, &m.LatencyP50Ms, &m.LatencyP95Ms, &m.LatencyP99Ms,
		&m.LatencyMaxMs, &m.LatencyAvgMs, &m.ErrorRate)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &m, nil
}

// GetThroughputHistory retrieves throughput over time for charting
func (s *Storage) GetThroughputHistory(limit int) ([]struct {
	RunID     string    `json:"run_id"`
	StartTime time.Time `json:"start_time"`
	GitCommit string    `json:"git_commit"`
	Throughput float64  `json:"throughput"`
}, error) {
	rows, err := s.db.Query(`
		SELECT r.run_id, r.start_time, r.git_commit, m.throughput_ops_per_sec
		FROM benchmark_runs r
		JOIN benchmark_metrics m ON r.run_id = m.run_id
		WHERE r.success = 1
		ORDER BY r.start_time DESC
		LIMIT ?`, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var history []struct {
		RunID     string    `json:"run_id"`
		StartTime time.Time `json:"start_time"`
		GitCommit string    `json:"git_commit"`
		Throughput float64  `json:"throughput"`
	}
	for rows.Next() {
		var h struct {
			RunID     string    `json:"run_id"`
			StartTime time.Time `json:"start_time"`
			GitCommit string    `json:"git_commit"`
			Throughput float64  `json:"throughput"`
		}
		if err := rows.Scan(&h.RunID, &h.StartTime, &h.GitCommit, &h.Throughput); err != nil {
			return nil, err
		}
		history = append(history, h)
	}
	return history, nil
}

// GetLatencyHistory retrieves latency percentiles over time
func (s *Storage) GetLatencyHistory(limit int) ([]struct {
	RunID     string    `json:"run_id"`
	StartTime time.Time `json:"start_time"`
	GitCommit string    `json:"git_commit"`
	P50       float64   `json:"p50"`
	P95       float64   `json:"p95"`
	P99       float64   `json:"p99"`
}, error) {
	rows, err := s.db.Query(`
		SELECT r.run_id, r.start_time, r.git_commit,
			m.latency_p50_ms, m.latency_p95_ms, m.latency_p99_ms
		FROM benchmark_runs r
		JOIN benchmark_metrics m ON r.run_id = m.run_id
		WHERE r.success = 1
		ORDER BY r.start_time DESC
		LIMIT ?`, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var history []struct {
		RunID     string    `json:"run_id"`
		StartTime time.Time `json:"start_time"`
		GitCommit string    `json:"git_commit"`
		P50       float64   `json:"p50"`
		P95       float64   `json:"p95"`
		P99       float64   `json:"p99"`
	}
	for rows.Next() {
		var h struct {
			RunID     string    `json:"run_id"`
			StartTime time.Time `json:"start_time"`
			GitCommit string    `json:"git_commit"`
			P50       float64   `json:"p50"`
			P95       float64   `json:"p95"`
			P99       float64   `json:"p99"`
		}
		if err := rows.Scan(&h.RunID, &h.StartTime, &h.GitCommit, &h.P50, &h.P95, &h.P99); err != nil {
			return nil, err
		}
		history = append(history, h)
	}
	return history, nil
}

// GetComponentMemoryHistory retrieves memory usage history for a component
func (s *Storage) GetComponentMemoryHistory(component string, limit int) ([]ComponentSnapshot, error) {
	rows, err := s.db.Query(`
		SELECT id, run_id, component, timestamp_ms, memory_used_mb, memory_limit_mb,
			memory_peak_mb, memory_percent, heap_used_mb, heap_max_mb, heap_percent,
			cpu_percent, threads_active, threads_max, gc_count, gc_time_ms, status, oom_risk
		FROM component_snapshots
		WHERE component = ?
		ORDER BY timestamp_ms DESC
		LIMIT ?`, component, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var snapshots []ComponentSnapshot
	for rows.Next() {
		var s ComponentSnapshot
		err := rows.Scan(&s.ID, &s.RunID, &s.Component, &s.TimestampMs, &s.MemoryUsedMB,
			&s.MemoryLimitMB, &s.MemoryPeakMB, &s.MemoryPercent, &s.HeapUsedMB,
			&s.HeapMaxMB, &s.HeapPercent, &s.CPUPercent, &s.ThreadsActive, &s.ThreadsMax,
			&s.GCCount, &s.GCTimeMs, &s.Status, &s.OOMRisk)
		if err != nil {
			return nil, err
		}
		snapshots = append(snapshots, s)
	}
	return snapshots, nil
}

// DiagnosticToSnapshot converts a DiagnosticSnapshot to a ComponentSnapshot for storage
func DiagnosticToSnapshot(runID, component string, diag *DiagnosticSnapshot, memLimitMB float64) (*ComponentSnapshot, error) {
	diagJSON, err := json.Marshal(diag)
	if err != nil {
		return nil, err
	}

	// Calculate memory peak from pools
	var peakMB float64
	for _, pool := range diag.Memory.MemoryPools {
		if pool.Name == "container_peak" {
			peakMB = float64(pool.UsedBytes) / 1024 / 1024
		}
	}

	// Determine status
	status := "healthy"
	oomRisk := diag.Errors.CrashIndicators.OOMRisk
	memPct := diag.Saturation.HeapUsedPercent
	if memPct == 0 && diag.Memory.NativeMemoryUsedBytes > 0 {
		memPct = float64(diag.Memory.NativeMemoryUsedBytes) / (memLimitMB * 1024 * 1024) * 100
	}
	if oomRisk || memPct > 90 {
		status = "critical"
	} else if memPct > 75 {
		status = "warning"
	}

	return &ComponentSnapshot{
		RunID:          runID,
		Component:      component,
		TimestampMs:    diag.TimestampMs,
		MemoryUsedMB:   float64(diag.Memory.NativeMemoryUsedBytes) / 1024 / 1024,
		MemoryLimitMB:  memLimitMB,
		MemoryPeakMB:   peakMB,
		MemoryPercent:  memPct,
		HeapUsedMB:     float64(diag.Memory.HeapUsedBytes) / 1024 / 1024,
		HeapMaxMB:      float64(diag.Memory.HeapMaxBytes) / 1024 / 1024,
		HeapPercent:    diag.Saturation.HeapUsedPercent,
		CPUPercent:     diag.Saturation.CPUPercent,
		ThreadsActive:  diag.Saturation.ThreadPoolActive,
		ThreadsMax:     diag.Saturation.ThreadPoolMax,
		GCCount:        diag.GC.YoungGCCount + diag.GC.OldGCCount,
		GCTimeMs:       diag.GC.YoungGCTimeMs + diag.GC.OldGCTimeMs,
		Status:         status,
		OOMRisk:        oomRisk,
		DiagnosticJSON: string(diagJSON),
	}, nil
}
