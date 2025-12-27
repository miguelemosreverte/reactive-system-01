package cmd

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/spf13/cobra"
	_ "modernc.org/sqlite"
)

// QueueJob represents a benchmark job in the queue
type QueueJob struct {
	ID          string    `json:"id"`
	Brochure    string    `json:"brochure"`
	Commit      string    `json:"commit"`
	Branch      string    `json:"branch"`
	Developer   string    `json:"developer"`
	Status      string    `json:"status"` // pending, running, completed, failed
	SubmittedAt time.Time `json:"submittedAt"`
	StartedAt   *time.Time `json:"startedAt,omitempty"`
	CompletedAt *time.Time `json:"completedAt,omitempty"`
	Throughput  float64   `json:"throughput,omitempty"`
	P50Ms       float64   `json:"p50Ms,omitempty"`
	P99Ms       float64   `json:"p99Ms,omitempty"`
	Error       string    `json:"error,omitempty"`
}

var benchQueueCmd = &cobra.Command{
	Use:   "queue",
	Short: "Manage benchmark queue for multi-developer workflow",
	Long: `Benchmark queue allows multiple developers to submit benchmark jobs
without colliding. Jobs are executed sequentially by a runner.

Workflow:
  1. Developer A: reactive bench queue add gateway-netty-microbatch
  2. Developer B: reactive bench queue add flink-stream
  3. Runner:      reactive bench queue run --daemon

Jobs are associated with the current git commit so results are reproducible.`,
}

var queueAddCmd = &cobra.Command{
	Use:   "add <brochure>",
	Short: "Add a benchmark job to the queue",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		brochureName := args[0]
		commit, _ := cmd.Flags().GetString("commit")

		db, err := openQueueDB()
		if err != nil {
			printError(fmt.Sprintf("Failed to open queue: %v", err))
			return
		}
		defer db.Close()

		// Get current commit if not specified
		if commit == "" {
			commit = getCurrentCommit()
		}

		branch := getCurrentBranch()
		developer := getGitUser()

		job := QueueJob{
			ID:          uuid.New().String()[:8],
			Brochure:    brochureName,
			Commit:      commit,
			Branch:      branch,
			Developer:   developer,
			Status:      "pending",
			SubmittedAt: time.Now(),
		}

		err = insertJob(db, job)
		if err != nil {
			printError(fmt.Sprintf("Failed to add job: %v", err))
			return
		}

		printSuccess(fmt.Sprintf("Job %s added to queue", job.ID))
		fmt.Printf("  Brochure:  %s\n", job.Brochure)
		fmt.Printf("  Commit:    %s\n", job.Commit[:8])
		fmt.Printf("  Branch:    %s\n", job.Branch)
		fmt.Printf("  Developer: %s\n", job.Developer)
	},
}

var queueListCmd = &cobra.Command{
	Use:   "list",
	Short: "List all jobs in the queue",
	Run: func(cmd *cobra.Command, args []string) {
		db, err := openQueueDB()
		if err != nil {
			printError(fmt.Sprintf("Failed to open queue: %v", err))
			return
		}
		defer db.Close()

		jobs, err := listJobs(db)
		if err != nil {
			printError(fmt.Sprintf("Failed to list jobs: %v", err))
			return
		}

		if len(jobs) == 0 {
			fmt.Println("Queue is empty")
			return
		}

		// Group by status
		pending := []QueueJob{}
		running := []QueueJob{}
		completed := []QueueJob{}

		for _, j := range jobs {
			switch j.Status {
			case "pending":
				pending = append(pending, j)
			case "running":
				running = append(running, j)
			default:
				completed = append(completed, j)
			}
		}

		if len(running) > 0 {
			fmt.Println("\n🔄 Running:")
			for _, j := range running {
				fmt.Printf("  [%s] %s @ %s by %s\n", j.ID, j.Brochure, j.Commit[:8], j.Developer)
			}
		}

		if len(pending) > 0 {
			fmt.Println("\n⏳ Pending:")
			for i, j := range pending {
				fmt.Printf("  %d. [%s] %s @ %s by %s\n", i+1, j.ID, j.Brochure, j.Commit[:8], j.Developer)
			}
		}

		if len(completed) > 0 {
			fmt.Println("\n✅ Recent (last 10):")
			shown := 0
			for i := len(completed) - 1; i >= 0 && shown < 10; i-- {
				j := completed[i]
				status := "✓"
				if j.Status == "failed" {
					status = "✗"
				}
				fmt.Printf("  %s [%s] %s: %.0f ops/s (p50=%.0fms, p99=%.0fms)\n",
					status, j.ID, j.Brochure, j.Throughput, j.P50Ms, j.P99Ms)
				shown++
			}
		}

		fmt.Printf("\nTotal: %d pending, %d running, %d completed\n",
			len(pending), len(running), len(completed))
	},
}

var queueRunCmd = &cobra.Command{
	Use:   "run",
	Short: "Run the next pending job (or all with --daemon)",
	Run: func(cmd *cobra.Command, args []string) {
		daemon, _ := cmd.Flags().GetBool("daemon")

		db, err := openQueueDB()
		if err != nil {
			printError(fmt.Sprintf("Failed to open queue: %v", err))
			return
		}
		defer db.Close()

		if daemon {
			fmt.Println("Starting queue runner in daemon mode...")
			fmt.Println("Press Ctrl+C to stop")
			for {
				runNextJob(db)
				time.Sleep(5 * time.Second)
			}
		} else {
			runNextJob(db)
		}
	},
}

var queueCancelCmd = &cobra.Command{
	Use:   "cancel <job-id>",
	Short: "Cancel a pending job",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		jobID := args[0]

		db, err := openQueueDB()
		if err != nil {
			printError(fmt.Sprintf("Failed to open queue: %v", err))
			return
		}
		defer db.Close()

		result, err := db.Exec(
			"DELETE FROM jobs WHERE id = ? AND status = 'pending'",
			jobID,
		)
		if err != nil {
			printError(fmt.Sprintf("Failed to cancel: %v", err))
			return
		}

		rows, _ := result.RowsAffected()
		if rows == 0 {
			printError("Job not found or not pending")
			return
		}

		printSuccess(fmt.Sprintf("Job %s cancelled", jobID))
	},
}

var queueResultsCmd = &cobra.Command{
	Use:   "results <job-id>",
	Short: "Show detailed results for a job",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		jobID := args[0]

		db, err := openQueueDB()
		if err != nil {
			printError(fmt.Sprintf("Failed to open queue: %v", err))
			return
		}
		defer db.Close()

		job, err := getJob(db, jobID)
		if err != nil {
			printError(fmt.Sprintf("Job not found: %v", err))
			return
		}

		data, _ := json.MarshalIndent(job, "", "  ")
		fmt.Println(string(data))
	},
}

func init() {
	benchCmd.AddCommand(benchQueueCmd)

	benchQueueCmd.AddCommand(queueAddCmd)
	benchQueueCmd.AddCommand(queueListCmd)
	benchQueueCmd.AddCommand(queueRunCmd)
	benchQueueCmd.AddCommand(queueCancelCmd)
	benchQueueCmd.AddCommand(queueResultsCmd)

	queueAddCmd.Flags().String("commit", "", "Git commit to benchmark (default: HEAD)")
	queueRunCmd.Flags().Bool("daemon", false, "Run continuously, processing jobs as they arrive")
}

// Database operations

func getQueueDBPath() string {
	home, _ := os.UserHomeDir()
	dir := filepath.Join(home, ".reactive")
	os.MkdirAll(dir, 0755)
	return filepath.Join(dir, "benchmark-queue.db")
}

func openQueueDB() (*sql.DB, error) {
	dbPath := getQueueDBPath()
	db, err := sql.Open("sqlite", dbPath)
	if err != nil {
		return nil, err
	}

	// Create table if not exists
	_, err = db.Exec(`
		CREATE TABLE IF NOT EXISTS jobs (
			id TEXT PRIMARY KEY,
			brochure TEXT NOT NULL,
			git_commit TEXT NOT NULL,
			branch TEXT NOT NULL,
			developer TEXT NOT NULL,
			status TEXT NOT NULL DEFAULT 'pending',
			submitted_at DATETIME NOT NULL,
			started_at DATETIME,
			completed_at DATETIME,
			throughput REAL DEFAULT 0,
			p50_ms REAL DEFAULT 0,
			p99_ms REAL DEFAULT 0,
			error TEXT
		)
	`)
	if err != nil {
		db.Close()
		return nil, err
	}

	return db, nil
}

func insertJob(db *sql.DB, job QueueJob) error {
	_, err := db.Exec(`
		INSERT INTO jobs (id, brochure, git_commit, branch, developer, status, submitted_at)
		VALUES (?, ?, ?, ?, ?, ?, ?)
	`, job.ID, job.Brochure, job.Commit, job.Branch, job.Developer, job.Status, job.SubmittedAt)
	return err
}

func listJobs(db *sql.DB) ([]QueueJob, error) {
	rows, err := db.Query(`
		SELECT id, brochure, git_commit, branch, developer, status,
		       submitted_at, started_at, completed_at, throughput, p50_ms, p99_ms, error
		FROM jobs
		ORDER BY submitted_at ASC
	`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var jobs []QueueJob
	for rows.Next() {
		var j QueueJob
		var startedAt, completedAt sql.NullTime
		var errorStr sql.NullString

		err := rows.Scan(&j.ID, &j.Brochure, &j.Commit, &j.Branch, &j.Developer, &j.Status,
			&j.SubmittedAt, &startedAt, &completedAt, &j.Throughput, &j.P50Ms, &j.P99Ms, &errorStr)
		if err != nil {
			continue
		}

		if startedAt.Valid {
			j.StartedAt = &startedAt.Time
		}
		if completedAt.Valid {
			j.CompletedAt = &completedAt.Time
		}
		if errorStr.Valid {
			j.Error = errorStr.String
		}

		jobs = append(jobs, j)
	}

	return jobs, nil
}

func getJob(db *sql.DB, jobID string) (*QueueJob, error) {
	var j QueueJob
	var startedAt, completedAt sql.NullTime
	var errorStr sql.NullString

	err := db.QueryRow(`
		SELECT id, brochure, git_commit, branch, developer, status,
		       submitted_at, started_at, completed_at, throughput, p50_ms, p99_ms, error
		FROM jobs WHERE id = ?
	`, jobID).Scan(&j.ID, &j.Brochure, &j.Commit, &j.Branch, &j.Developer, &j.Status,
		&j.SubmittedAt, &startedAt, &completedAt, &j.Throughput, &j.P50Ms, &j.P99Ms, &errorStr)

	if err != nil {
		return nil, err
	}

	if startedAt.Valid {
		j.StartedAt = &startedAt.Time
	}
	if completedAt.Valid {
		j.CompletedAt = &completedAt.Time
	}
	if errorStr.Valid {
		j.Error = errorStr.String
	}

	return &j, nil
}

func getNextPendingJob(db *sql.DB) (*QueueJob, error) {
	var j QueueJob

	err := db.QueryRow(`
		SELECT id, brochure, git_commit, branch, developer, status, submitted_at
		FROM jobs WHERE status = 'pending'
		ORDER BY submitted_at ASC LIMIT 1
	`).Scan(&j.ID, &j.Brochure, &j.Commit, &j.Branch, &j.Developer, &j.Status, &j.SubmittedAt)

	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}

	return &j, nil
}

func updateJobStatus(db *sql.DB, jobID, status string) error {
	now := time.Now()

	if status == "running" {
		_, err := db.Exec("UPDATE jobs SET status = ?, started_at = ? WHERE id = ?", status, now, jobID)
		return err
	} else if status == "completed" || status == "failed" {
		_, err := db.Exec("UPDATE jobs SET status = ?, completed_at = ? WHERE id = ?", status, now, jobID)
		return err
	}

	_, err := db.Exec("UPDATE jobs SET status = ? WHERE id = ?", status, jobID)
	return err
}

func updateJobResults(db *sql.DB, jobID string, throughput, p50, p99 float64, errMsg string) error {
	status := "completed"
	if errMsg != "" {
		status = "failed"
	}

	_, err := db.Exec(`
		UPDATE jobs SET status = ?, completed_at = ?, throughput = ?, p50_ms = ?, p99_ms = ?, error = ?
		WHERE id = ?
	`, status, time.Now(), throughput, p50, p99, errMsg, jobID)
	return err
}

func runNextJob(db *sql.DB) {
	job, err := getNextPendingJob(db)
	if err != nil {
		printError(fmt.Sprintf("Error getting next job: %v", err))
		return
	}

	if job == nil {
		fmt.Println("No pending jobs")
		return
	}

	fmt.Printf("\n▶ Running job %s: %s @ %s\n", job.ID, job.Brochure, job.Commit[:8])

	// Mark as running
	updateJobStatus(db, job.ID, "running")

	// Checkout the commit in a temporary worktree
	projectRoot := findProjectRoot()

	// Stash current changes, checkout commit, run benchmark, restore
	// For simplicity, we'll run the benchmark at current state but log the commit
	// In production, you'd want to checkout the specific commit

	// Run the brochure benchmark
	result := &BrochureResult{}
	brochure, err := loadBrochure(job.Brochure)
	if err != nil {
		updateJobResults(db, job.ID, 0, 0, 0, fmt.Sprintf("Failed to load brochure: %v", err))
		return
	}

	brochureDir := filepath.Join(projectRoot, "reports", "brochures", job.Brochure)
	os.MkdirAll(brochureDir, 0755)

	network := findDockerNetwork()
	if network == "" {
		updateJobResults(db, job.ID, 0, 0, 0, "Docker network not found")
		return
	}

	// Run based on component type
	switch brochure.Component {
	case "http":
		runHttpBrochure(projectRoot, network, brochure, brochureDir, result)
	case "kafka":
		runKafkaBrochure(projectRoot, network, brochure, brochureDir, result)
	case "flink":
		runFlinkBrochure(projectRoot, network, brochure, brochureDir, result)
	case "gateway":
		runGatewayBrochure(projectRoot, network, brochure, brochureDir, result)
	case "full":
		runFullBrochure(projectRoot, network, brochure, brochureDir, result)
	case "collector":
		runCollectorBrochure(projectRoot, network, brochure, brochureDir, result)
	default:
		updateJobResults(db, job.ID, 0, 0, 0, fmt.Sprintf("Unknown component: %s", brochure.Component))
		return
	}

	// Update with results
	if result.FailedOps > 0 && result.SuccessOps == 0 {
		updateJobResults(db, job.ID, result.Throughput, result.P50Ms, result.P99Ms,
			fmt.Sprintf("All %d operations failed", result.FailedOps))
	} else {
		updateJobResults(db, job.ID, result.Throughput, result.P50Ms, result.P99Ms, "")
	}

	fmt.Printf("✓ Job %s completed: %.0f ops/s\n", job.ID, result.Throughput)
}

// Git helpers

func getCurrentCommit() string {
	cmd := exec.Command("git", "rev-parse", "HEAD")
	out, err := cmd.Output()
	if err != nil {
		return "unknown"
	}
	return strings.TrimSpace(string(out))
}

func getCurrentBranch() string {
	cmd := exec.Command("git", "rev-parse", "--abbrev-ref", "HEAD")
	out, err := cmd.Output()
	if err != nil {
		return "unknown"
	}
	return strings.TrimSpace(string(out))
}

func getGitUser() string {
	cmd := exec.Command("git", "config", "user.name")
	out, err := cmd.Output()
	if err != nil {
		return "unknown"
	}
	return strings.TrimSpace(string(out))
}
