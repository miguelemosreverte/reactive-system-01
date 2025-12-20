package api

import (
	"encoding/json"
	"log"
	"net/http"
	"os"

	"github.com/gorilla/mux"
	"github.com/reactive/benchmark/pkg/report"
	"github.com/reactive/benchmark/pkg/runner"
	"github.com/reactive/benchmark/pkg/types"
)

// Server is the HTTP API server for benchmarks
type Server struct {
	registry  *runner.Registry
	reporter  *report.Generator
	apiKey    string
	router    *mux.Router
	reportDir string
}

// NewServer creates a new API server
func NewServer(registry *runner.Registry, reportDir string) *Server {
	apiKey := os.Getenv("API_KEY")
	if apiKey == "" {
		apiKey = "reactive-admin-key"
	}

	s := &Server{
		registry:  registry,
		reporter:  report.NewGenerator(reportDir),
		apiKey:    apiKey,
		router:    mux.NewRouter(),
		reportDir: reportDir,
	}

	s.setupRoutes()
	return s
}

func (s *Server) setupRoutes() {
	// Health check (no auth required)
	s.router.HandleFunc("/health", s.handleHealth).Methods("GET")

	// API routes (auth required)
	api := s.router.PathPrefix("/api").Subrouter()
	api.Use(s.authMiddleware)

	// Benchmark endpoints
	api.HandleFunc("/benchmark/all", s.handleRunAll).Methods("POST")
	api.HandleFunc("/benchmark/{component}", s.handleRunComponent).Methods("POST")
	api.HandleFunc("/benchmark/stop", s.handleStop).Methods("POST")
	api.HandleFunc("/benchmark/status", s.handleStatus).Methods("GET")
	api.HandleFunc("/benchmark/results", s.handleAllResults).Methods("GET")
	api.HandleFunc("/benchmark/results/{component}", s.handleComponentResult).Methods("GET")

	// Report generation (order matters: specific before pattern)
	api.HandleFunc("/report/all", s.handleGenerateAllReports).Methods("POST")
	api.HandleFunc("/report/{component}", s.handleGenerateReport).Methods("POST")

	// Static file server for reports
	s.router.PathPrefix("/reports/").Handler(
		http.StripPrefix("/reports/", http.FileServer(http.Dir(s.reportDir))),
	)
}

func (s *Server) authMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		apiKey := r.Header.Get("X-API-Key")
		if apiKey == "" {
			apiKey = r.URL.Query().Get("apiKey")
		}

		if apiKey != s.apiKey {
			http.Error(w, `{"error":"Unauthorized"}`, http.StatusUnauthorized)
			return
		}
		next.ServeHTTP(w, r)
	})
}

func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	json.NewEncoder(w).Encode(map[string]interface{}{
		"status":  "healthy",
		"service": "benchmark",
	})
}

func (s *Server) handleRunAll(w http.ResponseWriter, r *http.Request) {
	if s.registry.IsAnyRunning() {
		http.Error(w, `{"error":"A benchmark is already running"}`, http.StatusConflict)
		return
	}

	var config types.Config
	if err := json.NewDecoder(r.Body).Decode(&config); err != nil {
		config = types.DefaultConfig()
	}

	// Merge with defaults
	defaultConfig := types.DefaultConfig()
	if config.DurationMs == 0 {
		config.DurationMs = defaultConfig.DurationMs
	}
	if config.Concurrency == 0 {
		config.Concurrency = defaultConfig.Concurrency
	}
	if config.GatewayURL == "" {
		config.GatewayURL = defaultConfig.GatewayURL
	}
	if config.DroolsURL == "" {
		config.DroolsURL = defaultConfig.DroolsURL
	}

	go func() {
		results, err := s.registry.RunAll(config)
		if err != nil {
			log.Printf("RunAll error: %v", err)
		}
		// Generate reports for all completed benchmarks
		for id, result := range results {
			if result != nil {
				if err := s.reporter.Generate(result); err != nil {
					log.Printf("Report generation error for %s: %v", id, err)
				}
			}
		}
	}()

	json.NewEncoder(w).Encode(map[string]interface{}{
		"message": "All benchmarks started",
		"config":  config,
	})
}

func (s *Server) handleRunComponent(w http.ResponseWriter, r *http.Request) {
	vars := mux.Vars(r)
	componentStr := vars["component"]
	component := types.ComponentID(componentStr)

	if s.registry.IsAnyRunning() {
		http.Error(w, `{"error":"A benchmark is already running"}`, http.StatusConflict)
		return
	}

	var config types.Config
	if err := json.NewDecoder(r.Body).Decode(&config); err != nil {
		config = types.DefaultConfig()
	}

	// Merge with defaults
	defaultConfig := types.DefaultConfig()
	if config.DurationMs == 0 {
		config.DurationMs = defaultConfig.DurationMs
	}
	if config.Concurrency == 0 {
		config.Concurrency = defaultConfig.Concurrency
	}
	if config.GatewayURL == "" {
		config.GatewayURL = defaultConfig.GatewayURL
	}
	if config.DroolsURL == "" {
		config.DroolsURL = defaultConfig.DroolsURL
	}

	go func() {
		result, err := s.registry.Run(component, config)
		if err != nil {
			log.Printf("Benchmark error: %v", err)
			return
		}
		if err := s.reporter.Generate(result); err != nil {
			log.Printf("Report generation error: %v", err)
		}
	}()

	json.NewEncoder(w).Encode(map[string]interface{}{
		"message":   "Benchmark started",
		"component": component,
		"config":    config,
	})
}

func (s *Server) handleStop(w http.ResponseWriter, r *http.Request) {
	s.registry.StopAll()
	json.NewEncoder(w).Encode(map[string]string{"message": "All benchmarks stopped"})
}

func (s *Server) handleStatus(w http.ResponseWriter, r *http.Request) {
	json.NewEncoder(w).Encode(map[string]interface{}{
		"running":    s.registry.IsAnyRunning(),
		"components": s.registry.ListComponents(),
	})
}

func (s *Server) handleAllResults(w http.ResponseWriter, r *http.Request) {
	results := s.registry.GetAllResults()
	json.NewEncoder(w).Encode(results)
}

func (s *Server) handleComponentResult(w http.ResponseWriter, r *http.Request) {
	vars := mux.Vars(r)
	component := types.ComponentID(vars["component"])

	result := s.registry.GetResult(component)
	if result == nil {
		http.Error(w, `{"error":"No result found"}`, http.StatusNotFound)
		return
	}

	json.NewEncoder(w).Encode(result)
}

func (s *Server) handleGenerateReport(w http.ResponseWriter, r *http.Request) {
	vars := mux.Vars(r)
	component := types.ComponentID(vars["component"])

	result := s.registry.GetResult(component)
	if result == nil {
		http.Error(w, `{"error":"No result found for this component"}`, http.StatusNotFound)
		return
	}

	if err := s.reporter.Generate(result); err != nil {
		http.Error(w, `{"error":"`+err.Error()+`"}`, http.StatusInternalServerError)
		return
	}

	json.NewEncoder(w).Encode(map[string]string{
		"message": "Report generated",
		"path":    s.reportDir + "/" + string(component),
	})
}

func (s *Server) handleGenerateAllReports(w http.ResponseWriter, r *http.Request) {
	results := s.registry.GetAllResults()
	generated := []string{}

	for id, result := range results {
		if result != nil {
			if err := s.reporter.Generate(result); err != nil {
				log.Printf("Report generation error for %s: %v", id, err)
			} else {
				generated = append(generated, string(id))
			}
		}
	}

	// Generate index page
	s.reporter.GenerateIndex(results)

	json.NewEncoder(w).Encode(map[string]interface{}{
		"message":   "Reports generated",
		"generated": generated,
	})
}

// Start starts the HTTP server
func (s *Server) Start(addr string) error {
	log.Printf("Starting benchmark API server on %s", addr)
	return http.ListenAndServe(addr, s.router)
}

// Router returns the HTTP router for testing
func (s *Server) Router() *mux.Router {
	return s.router
}
