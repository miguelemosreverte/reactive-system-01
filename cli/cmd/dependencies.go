package cmd

import (
	"fmt"
	"os/exec"
	"runtime"
	"strings"
)

// DependencyCheck represents the result of checking a dependency
type DependencyCheck struct {
	Name      string
	Available bool
	Version   string
	Path      string
	Error     string
}

// DependencyResult holds all dependency check results
type DependencyResult struct {
	Mode    string // "docker" or "native"
	Checks  []DependencyCheck
	AllMet  bool
	Missing []string
}

// CheckDockerDependencies verifies Docker is available for Docker mode
func CheckDockerDependencies() DependencyResult {
	result := DependencyResult{Mode: "docker"}

	// Check Docker
	dockerCheck := checkCommand("docker", "--version")
	result.Checks = append(result.Checks, dockerCheck)

	// Check Docker daemon is running
	if dockerCheck.Available {
		daemonCheck := checkDockerDaemon()
		result.Checks = append(result.Checks, daemonCheck)
	}

	// Determine if all dependencies are met
	result.AllMet = true
	for _, check := range result.Checks {
		if !check.Available {
			result.AllMet = false
			result.Missing = append(result.Missing, check.Name)
		}
	}

	return result
}

// CheckNativeDependencies verifies Java and Maven are available for native mode
func CheckNativeDependencies() DependencyResult {
	result := DependencyResult{Mode: "native"}

	// Check Java
	javaCheck := checkCommand("java", "-version")
	result.Checks = append(result.Checks, javaCheck)

	// Check Maven
	mvnCheck := checkCommand("mvn", "--version")
	result.Checks = append(result.Checks, mvnCheck)

	// Check Java version is 21+
	if javaCheck.Available {
		javaVersionCheck := checkJavaVersion()
		result.Checks = append(result.Checks, javaVersionCheck)
	}

	// Determine if all dependencies are met
	result.AllMet = true
	for _, check := range result.Checks {
		if !check.Available {
			result.AllMet = false
			result.Missing = append(result.Missing, check.Name)
		}
	}

	return result
}

// checkCommand checks if a command is available and gets its version
func checkCommand(name string, versionArg string) DependencyCheck {
	check := DependencyCheck{Name: name}

	// Check if command exists
	path, err := exec.LookPath(name)
	if err != nil {
		check.Available = false
		check.Error = fmt.Sprintf("%s not found in PATH", name)
		return check
	}
	check.Path = path

	// Get version
	cmd := exec.Command(name, versionArg)
	output, err := cmd.CombinedOutput()
	if err != nil {
		check.Available = false
		check.Error = fmt.Sprintf("failed to run %s %s: %v", name, versionArg, err)
		return check
	}

	check.Available = true
	// Extract first line as version
	lines := strings.Split(string(output), "\n")
	if len(lines) > 0 {
		check.Version = strings.TrimSpace(lines[0])
	}

	return check
}

// checkDockerDaemon verifies the Docker daemon is running
func checkDockerDaemon() DependencyCheck {
	check := DependencyCheck{Name: "Docker Daemon"}

	cmd := exec.Command("docker", "info")
	_, err := cmd.CombinedOutput()
	if err != nil {
		check.Available = false
		check.Error = "Docker daemon is not running"
		return check
	}

	check.Available = true
	check.Version = "running"
	return check
}

// checkJavaVersion verifies Java is version 21 or higher
func checkJavaVersion() DependencyCheck {
	check := DependencyCheck{Name: "Java 21+"}

	cmd := exec.Command("java", "-version")
	output, err := cmd.CombinedOutput()
	if err != nil {
		check.Available = false
		check.Error = "failed to get Java version"
		return check
	}

	// Parse version from output (e.g., "openjdk version "21.0.1"")
	outputStr := string(output)

	// Check for version 21 or higher
	if strings.Contains(outputStr, "\"21") ||
		strings.Contains(outputStr, "\"22") ||
		strings.Contains(outputStr, "\"23") ||
		strings.Contains(outputStr, "\"24") ||
		strings.Contains(outputStr, "\"25") {
		check.Available = true
		check.Version = "21+"
		return check
	}

	check.Available = false
	check.Error = "Java 21 or higher required"
	return check
}

// PrintDependencyResult prints dependency check results
func PrintDependencyResult(result DependencyResult) {
	if result.AllMet {
		printSuccess(fmt.Sprintf("All %s dependencies available", result.Mode))
		for _, check := range result.Checks {
			if check.Available {
				fmt.Printf("  ✓ %s: %s\n", check.Name, check.Version)
			}
		}
		return
	}

	printError(fmt.Sprintf("Missing %s dependencies:", result.Mode))
	for _, check := range result.Checks {
		if check.Available {
			fmt.Printf("  ✓ %s: %s\n", check.Name, check.Version)
		} else {
			fmt.Printf("  ✗ %s: %s\n", check.Name, check.Error)
		}
	}

	fmt.Println()
	printInstallInstructions(result)
}

// printInstallInstructions prints installation instructions for missing dependencies
func printInstallInstructions(result DependencyResult) {
	printInfo("Installation instructions:")
	fmt.Println()

	os := runtime.GOOS

	for _, missing := range result.Missing {
		switch missing {
		case "docker", "Docker Daemon":
			printDockerInstallInstructions(os)
		case "java", "Java 21+":
			printJavaInstallInstructions(os)
		case "mvn":
			printMavenInstallInstructions(os)
		}
	}
}

func printDockerInstallInstructions(os string) {
	fmt.Println("  Docker:")
	switch os {
	case "darwin":
		fmt.Println("    # Option 1: Docker Desktop (recommended)")
		fmt.Println("    brew install --cask docker")
		fmt.Println("    # Then open Docker.app from Applications")
		fmt.Println()
		fmt.Println("    # Option 2: Colima (lightweight alternative)")
		fmt.Println("    brew install colima docker")
		fmt.Println("    colima start")
		fmt.Println()
	case "linux":
		fmt.Println("    # Ubuntu/Debian:")
		fmt.Println("    curl -fsSL https://get.docker.com | sudo sh")
		fmt.Println("    sudo usermod -aG docker $USER")
		fmt.Println("    # Log out and back in, then:")
		fmt.Println("    sudo systemctl start docker")
		fmt.Println()
	case "windows":
		fmt.Println("    # Download Docker Desktop from:")
		fmt.Println("    https://www.docker.com/products/docker-desktop")
		fmt.Println()
	}
}

func printJavaInstallInstructions(os string) {
	fmt.Println("  Java 21:")
	switch os {
	case "darwin":
		fmt.Println("    # Using Homebrew:")
		fmt.Println("    brew install openjdk@21")
		fmt.Println("    sudo ln -sfn $(brew --prefix)/opt/openjdk@21/libexec/openjdk.jdk /Library/Java/JavaVirtualMachines/openjdk-21.jdk")
		fmt.Println()
		fmt.Println("    # Or using SDKMAN (recommended for multiple versions):")
		fmt.Println("    curl -s \"https://get.sdkman.io\" | bash")
		fmt.Println("    sdk install java 21-tem")
		fmt.Println()
	case "linux":
		fmt.Println("    # Ubuntu/Debian:")
		fmt.Println("    sudo apt update && sudo apt install openjdk-21-jdk")
		fmt.Println()
		fmt.Println("    # Or using SDKMAN:")
		fmt.Println("    curl -s \"https://get.sdkman.io\" | bash")
		fmt.Println("    sdk install java 21-tem")
		fmt.Println()
	case "windows":
		fmt.Println("    # Download from:")
		fmt.Println("    https://adoptium.net/temurin/releases/?version=21")
		fmt.Println()
	}
}

func printMavenInstallInstructions(os string) {
	fmt.Println("  Maven:")
	switch os {
	case "darwin":
		fmt.Println("    brew install maven")
		fmt.Println()
	case "linux":
		fmt.Println("    # Ubuntu/Debian:")
		fmt.Println("    sudo apt update && sudo apt install maven")
		fmt.Println()
		fmt.Println("    # Or using SDKMAN:")
		fmt.Println("    sdk install maven")
		fmt.Println()
	case "windows":
		fmt.Println("    # Download from:")
		fmt.Println("    https://maven.apache.org/download.cgi")
		fmt.Println()
	}
}

// GetDockerOrNativeMode determines which mode to use
// Returns "docker" if Docker is available, "native" if only native tools are available
// Returns error if neither is available
func GetDockerOrNativeMode(preferNative bool) (string, error) {
	if preferNative {
		nativeResult := CheckNativeDependencies()
		if nativeResult.AllMet {
			return "native", nil
		}
		PrintDependencyResult(nativeResult)
		return "", fmt.Errorf("native dependencies not met")
	}

	// Try Docker first
	dockerResult := CheckDockerDependencies()
	if dockerResult.AllMet {
		return "docker", nil
	}

	// Docker not available, try native
	nativeResult := CheckNativeDependencies()
	if nativeResult.AllMet {
		printWarning("Docker not available, falling back to native mode")
		return "native", nil
	}

	// Neither available
	fmt.Println()
	printError("Neither Docker nor native dependencies are available")
	fmt.Println()
	fmt.Println("Choose one of the following:")
	fmt.Println()
	fmt.Println("Option 1: Docker (recommended - no local setup needed)")
	PrintDependencyResult(dockerResult)
	fmt.Println()
	fmt.Println("Option 2: Native (requires local Java/Maven)")
	PrintDependencyResult(nativeResult)

	return "", fmt.Errorf("no execution environment available")
}
