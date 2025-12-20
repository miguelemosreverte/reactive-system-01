package runner

import (
	"fmt"
	"sync"

	"github.com/reactive/benchmark/pkg/types"
)

// Registry manages all benchmark runners
type Registry struct {
	runners map[types.ComponentID]types.Benchmark
	results map[types.ComponentID]*types.Result
	mu      sync.RWMutex
}

// NewRegistry creates a new benchmark registry with all runners
func NewRegistry() *Registry {
	r := &Registry{
		runners: make(map[types.ComponentID]types.Benchmark),
		results: make(map[types.ComponentID]*types.Result),
	}

	// Register all benchmark types
	// Note: Only benchmarks that test actual production components
	r.Register(NewHTTPRunner())
	r.Register(NewDroolsRunner())
	r.Register(NewGatewayRunner())
	r.Register(NewFullRunner())

	return r
}

// Register adds a benchmark runner to the registry
func (r *Registry) Register(b types.Benchmark) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.runners[b.ID()] = b
}

// Get returns a benchmark runner by ID
func (r *Registry) Get(id types.ComponentID) (types.Benchmark, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()

	runner, ok := r.runners[id]
	if !ok {
		return nil, fmt.Errorf("unknown benchmark component: %s", id)
	}
	return runner, nil
}

// Run executes a benchmark and stores the result
func (r *Registry) Run(id types.ComponentID, config types.Config) (*types.Result, error) {
	runner, err := r.Get(id)
	if err != nil {
		return nil, err
	}

	result, err := runner.Run(config)
	if err != nil {
		return nil, err
	}

	r.mu.Lock()
	r.results[id] = result
	r.mu.Unlock()

	return result, nil
}

// RunAll executes all benchmarks sequentially
func (r *Registry) RunAll(config types.Config) (map[types.ComponentID]*types.Result, error) {
	results := make(map[types.ComponentID]*types.Result)

	for _, id := range types.AllComponents {
		runner, err := r.Get(id)
		if err != nil {
			continue // Skip unregistered components
		}

		result, err := runner.Run(config)
		if err != nil {
			return results, fmt.Errorf("benchmark %s failed: %w", id, err)
		}

		r.mu.Lock()
		r.results[id] = result
		r.mu.Unlock()

		results[id] = result
	}

	return results, nil
}

// StopAll stops all running benchmarks
func (r *Registry) StopAll() {
	r.mu.RLock()
	defer r.mu.RUnlock()

	for _, runner := range r.runners {
		if runner.IsRunning() {
			runner.Stop()
		}
	}
}

// GetResult returns the last result for a component
func (r *Registry) GetResult(id types.ComponentID) *types.Result {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return r.results[id]
}

// GetAllResults returns all stored results
func (r *Registry) GetAllResults() map[types.ComponentID]*types.Result {
	r.mu.RLock()
	defer r.mu.RUnlock()

	results := make(map[types.ComponentID]*types.Result)
	for k, v := range r.results {
		results[k] = v
	}
	return results
}

// IsAnyRunning returns true if any benchmark is running
func (r *Registry) IsAnyRunning() bool {
	r.mu.RLock()
	defer r.mu.RUnlock()

	for _, runner := range r.runners {
		if runner.IsRunning() {
			return true
		}
	}
	return false
}

// ListComponents returns all registered component IDs
func (r *Registry) ListComponents() []types.ComponentID {
	r.mu.RLock()
	defer r.mu.RUnlock()

	ids := make([]types.ComponentID, 0, len(r.runners))
	for id := range r.runners {
		ids = append(ids, id)
	}
	return ids
}
