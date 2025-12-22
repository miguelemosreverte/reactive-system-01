// Package repl provides an interactive menu-driven CLI interface
package repl

import (
	"bufio"
	"fmt"
	"os"
	"os/exec"
	"strings"

	"golang.org/x/term"
)

// MenuItem represents a menu item
type MenuItem struct {
	Name        string
	Description string
	Command     string   // If set, execute this command
	SubMenu     []MenuItem // If set, show submenu
}

// Menu represents a navigable menu
type Menu struct {
	Title    string
	Items    []MenuItem
	selected int
	parent   *Menu
}

// REPL runs the interactive menu
type REPL struct {
	rootMenu *Menu
	current  *Menu
	oldState *term.State
}

// New creates a new REPL
func New() *REPL {
	root := buildRootMenu()
	return &REPL{
		rootMenu: root,
		current:  root,
	}
}

func buildRootMenu() *Menu {
	return &Menu{
		Title: "Reactive System CLI",
		Items: []MenuItem{
			{
				Name:        "Lifecycle",
				Description: "Start, stop, and manage services",
				SubMenu: []MenuItem{
					{Name: "start", Description: "Start all services", Command: "start"},
					{Name: "stop", Description: "Stop all services", Command: "stop"},
					{Name: "restart", Description: "Restart services", Command: "restart"},
					{Name: "rebuild", Description: "Rebuild a service", Command: "rebuild"},
					{Name: "status", Description: "Show service status", Command: "status"},
					{Name: "down", Description: "Stop and remove containers", Command: "down"},
					{Name: "clean", Description: "Remove everything", Command: "clean"},
				},
			},
			{
				Name:        "Diagnostics",
				Description: "Health checks and performance",
				SubMenu: []MenuItem{
					{Name: "doctor", Description: "Comprehensive health check", Command: "doctor"},
					{Name: "stats", Description: "Container resource usage", Command: "stats"},
					{Name: "diagnose", Description: "Memory diagnostics", Command: "diagnose"},
					{Name: "diagnose pressure", Description: "Pressure visualization", Command: "diagnose pressure"},
					{Name: "diagnose verdict", Description: "Actionable conclusions", Command: "diagnose verdict"},
				},
			},
			{
				Name:        "Benchmarking",
				Description: "Performance benchmarks",
				SubMenu: []MenuItem{
					{Name: "bench drools", Description: "Benchmark Drools API", Command: "bench drools"},
					{Name: "bench full", Description: "Benchmark full pipeline", Command: "bench full"},
					{Name: "bench gateway", Description: "Benchmark Gateway", Command: "bench gateway"},
				},
			},
			{
				Name:        "Observability",
				Description: "Traces and logs",
				SubMenu: []MenuItem{
					{Name: "trace", Description: "List recent traces", Command: "trace"},
					{Name: "logs", Description: "Search logs", Command: "logs"},
				},
			},
			{
				Name:        "Development",
				Description: "Development tools",
				SubMenu: []MenuItem{
					{Name: "dev", Description: "Start dev mode", Command: "dev"},
					{Name: "shell", Description: "Enter container shell", Command: "shell"},
					{Name: "follow", Description: "Follow service logs", Command: "follow"},
				},
			},
			{
				Name:        "Exit",
				Description: "Exit REPL",
				Command:     "exit",
			},
		},
	}
}

// Run starts the interactive REPL
func (r *REPL) Run() error {
	// Check if we have a terminal
	if !term.IsTerminal(int(os.Stdin.Fd())) {
		return fmt.Errorf("REPL requires an interactive terminal")
	}

	// Set terminal to raw mode
	oldState, err := term.MakeRaw(int(os.Stdin.Fd()))
	if err != nil {
		return r.runSimple() // Fallback to simple mode
	}
	r.oldState = oldState
	defer term.Restore(int(os.Stdin.Fd()), oldState)

	return r.runInteractive()
}

func (r *REPL) runInteractive() error {
	reader := bufio.NewReader(os.Stdin)

	for {
		r.render()

		// Read key
		b, err := reader.ReadByte()
		if err != nil {
			return err
		}

		switch b {
		case 27: // Escape sequence
			// Read next bytes for arrow keys
			reader.ReadByte() // [
			arrow, _ := reader.ReadByte()
			switch arrow {
			case 'A': // Up
				r.moveUp()
			case 'B': // Down
				r.moveDown()
			case 'C': // Right - enter submenu
				r.enter()
			case 'D': // Left - go back
				r.back()
			}
		case 13: // Enter
			if cmd := r.enter(); cmd == "exit" {
				return nil
			}
		case 'q', 3: // q or Ctrl+C
			return nil
		case 'h', '?': // Help
			r.showHelp()
		}
	}
}

func (r *REPL) render() {
	// Clear screen
	fmt.Print("\033[H\033[2J")

	// Header
	fmt.Println("\033[1m" + r.current.Title + "\033[0m")
	fmt.Println(strings.Repeat("─", 50))
	fmt.Println()

	// Show breadcrumb if in submenu
	if r.current.parent != nil {
		fmt.Printf("\033[90m← Back (Left Arrow) | %s\033[0m\n\n", r.current.Title)
	}

	// Items
	for i, item := range r.current.Items {
		prefix := "  "
		suffix := ""

		if i == r.current.selected {
			prefix = "\033[7m→ " // Inverted colors
			suffix = "\033[0m"
		}

		arrow := ""
		if len(item.SubMenu) > 0 {
			arrow = " →"
		}

		fmt.Printf("%s%-20s %s%s%s\n", prefix, item.Name+arrow, item.Description, suffix, "")
	}

	// Footer
	fmt.Println()
	fmt.Println(strings.Repeat("─", 50))
	fmt.Println("\033[90m↑↓ Navigate  Enter/→ Select  ← Back  q Quit\033[0m")
}

func (r *REPL) moveUp() {
	if r.current.selected > 0 {
		r.current.selected--
	}
}

func (r *REPL) moveDown() {
	if r.current.selected < len(r.current.Items)-1 {
		r.current.selected++
	}
}

func (r *REPL) enter() string {
	item := r.current.Items[r.current.selected]

	if item.Command == "exit" {
		return "exit"
	}

	if len(item.SubMenu) > 0 {
		// Enter submenu
		submenu := &Menu{
			Title:  item.Name,
			Items:  item.SubMenu,
			parent: r.current,
		}
		r.current = submenu
		return ""
	}

	if item.Command != "" {
		// Execute command
		r.executeCommand(item.Command)
		return ""
	}

	return ""
}

func (r *REPL) back() {
	if r.current.parent != nil {
		r.current = r.current.parent
	}
}

func (r *REPL) executeCommand(cmdStr string) {
	// Restore terminal
	if r.oldState != nil {
		term.Restore(int(os.Stdin.Fd()), r.oldState)
	}

	fmt.Print("\033[H\033[2J") // Clear screen
	fmt.Printf("\033[1mExecuting: reactive %s\033[0m\n\n", cmdStr)

	// Run command
	args := strings.Fields(cmdStr)
	cmd := exec.Command(os.Args[0], args...)
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	cmd.Stdin = os.Stdin
	cmd.Run()

	fmt.Println()
	fmt.Println("\033[90mPress any key to continue...\033[0m")

	// Wait for keypress
	reader := bufio.NewReader(os.Stdin)

	// Re-enable raw mode for keypress
	if r.oldState != nil {
		term.MakeRaw(int(os.Stdin.Fd()))
	}
	reader.ReadByte()
}

func (r *REPL) showHelp() {
	if r.oldState != nil {
		term.Restore(int(os.Stdin.Fd()), r.oldState)
	}

	fmt.Print("\033[H\033[2J")
	fmt.Println("\033[1mREPL Help\033[0m")
	fmt.Println(strings.Repeat("─", 50))
	fmt.Println()
	fmt.Println("Navigation:")
	fmt.Println("  ↑/↓     Move selection up/down")
	fmt.Println("  →/Enter Enter submenu or execute command")
	fmt.Println("  ←       Go back to parent menu")
	fmt.Println("  q       Quit REPL")
	fmt.Println("  ?/h     Show this help")
	fmt.Println()
	fmt.Println("You can also type commands directly:")
	fmt.Println("  /start  Execute 'start' command")
	fmt.Println("  /bench drools  Execute 'bench drools'")
	fmt.Println()
	fmt.Println("\033[90mPress any key to continue...\033[0m")

	reader := bufio.NewReader(os.Stdin)
	if r.oldState != nil {
		term.MakeRaw(int(os.Stdin.Fd()))
	}
	reader.ReadByte()
}

// runSimple provides a fallback simple menu for non-interactive terminals
func (r *REPL) runSimple() error {
	reader := bufio.NewReader(os.Stdin)

	for {
		fmt.Println()
		fmt.Println("Reactive System CLI")
		fmt.Println(strings.Repeat("─", 40))
		fmt.Println()

		for i, item := range r.current.Items {
			arrow := ""
			if len(item.SubMenu) > 0 {
				arrow = " →"
			}
			fmt.Printf("  %d. %s%s - %s\n", i+1, item.Name, arrow, item.Description)
		}

		if r.current.parent != nil {
			fmt.Printf("  0. ← Back\n")
		}

		fmt.Println()
		fmt.Print("Enter choice (or 'q' to quit): ")

		input, _ := reader.ReadString('\n')
		input = strings.TrimSpace(input)

		if input == "q" || input == "quit" {
			return nil
		}

		if input == "0" && r.current.parent != nil {
			r.current = r.current.parent
			continue
		}

		var choice int
		if _, err := fmt.Sscanf(input, "%d", &choice); err != nil || choice < 1 || choice > len(r.current.Items) {
			fmt.Println("Invalid choice")
			continue
		}

		item := r.current.Items[choice-1]

		if item.Command == "exit" {
			return nil
		}

		if len(item.SubMenu) > 0 {
			submenu := &Menu{
				Title:  item.Name,
				Items:  item.SubMenu,
				parent: r.current,
			}
			r.current = submenu
			continue
		}

		if item.Command != "" {
			fmt.Printf("\nExecuting: reactive %s\n\n", item.Command)
			args := strings.Fields(item.Command)
			cmd := exec.Command(os.Args[0], args...)
			cmd.Stdout = os.Stdout
			cmd.Stderr = os.Stderr
			cmd.Stdin = os.Stdin
			cmd.Run()
		}
	}
}
