package diagnostic

import (
	"encoding/json"
	"fmt"
	"io"
)

// JSONRenderer outputs diagnostic data as formatted JSON
type JSONRenderer struct {
	Pretty bool
}

// NewJSONRenderer creates a new JSON renderer
func NewJSONRenderer(pretty bool) *JSONRenderer {
	return &JSONRenderer{Pretty: pretty}
}

// Render outputs the diagnostic snapshot as JSON
func (r *JSONRenderer) Render(w io.Writer, snapshot *DiagnosticSnapshot) error {
	var data []byte
	var err error

	if r.Pretty {
		data, err = json.MarshalIndent(snapshot, "", "  ")
	} else {
		data, err = json.Marshal(snapshot)
	}

	if err != nil {
		return fmt.Errorf("failed to marshal diagnostic snapshot: %w", err)
	}

	_, err = w.Write(data)
	return err
}

// RenderMultiple outputs multiple snapshots as a JSON array
func (r *JSONRenderer) RenderMultiple(w io.Writer, snapshots []*DiagnosticSnapshot) error {
	var data []byte
	var err error

	if r.Pretty {
		data, err = json.MarshalIndent(snapshots, "", "  ")
	} else {
		data, err = json.Marshal(snapshots)
	}

	if err != nil {
		return fmt.Errorf("failed to marshal diagnostic snapshots: %w", err)
	}

	_, err = w.Write(data)
	return err
}
