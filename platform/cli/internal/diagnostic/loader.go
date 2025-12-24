package diagnostic

import (
	"encoding/json"
	"fmt"
	"io"
	"os"
)

// LoadFromFile loads a diagnostic snapshot from a JSON file
func LoadFromFile(path string) (*DiagnosticSnapshot, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, fmt.Errorf("failed to open file: %w", err)
	}
	defer f.Close()

	return LoadFromReader(f)
}

// LoadFromReader loads a diagnostic snapshot from a reader
func LoadFromReader(r io.Reader) (*DiagnosticSnapshot, error) {
	var snapshot DiagnosticSnapshot
	decoder := json.NewDecoder(r)
	if err := decoder.Decode(&snapshot); err != nil {
		return nil, fmt.Errorf("failed to decode JSON: %w", err)
	}
	return &snapshot, nil
}

// LoadMultipleFromFile loads multiple diagnostic snapshots from a JSON array file
func LoadMultipleFromFile(path string) ([]*DiagnosticSnapshot, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, fmt.Errorf("failed to open file: %w", err)
	}
	defer f.Close()

	return LoadMultipleFromReader(f)
}

// LoadMultipleFromReader loads multiple diagnostic snapshots from a reader
func LoadMultipleFromReader(r io.Reader) ([]*DiagnosticSnapshot, error) {
	var snapshots []*DiagnosticSnapshot
	decoder := json.NewDecoder(r)
	if err := decoder.Decode(&snapshots); err != nil {
		return nil, fmt.Errorf("failed to decode JSON array: %w", err)
	}
	return snapshots, nil
}
