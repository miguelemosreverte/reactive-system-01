package main

import (
	"fmt"
	"os"

	"github.com/reactive-system/cli/cmd"
	"github.com/reactive-system/cli/internal/repl"
)

func main() {
	// If no arguments, start REPL
	if len(os.Args) == 1 {
		r := repl.New()
		if err := r.Run(); err != nil {
			fmt.Fprintln(os.Stderr, "REPL error:", err)
			// Fall through to show help
		} else {
			return
		}
	}

	if err := cmd.Execute(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
