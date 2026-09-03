package main

import (
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

const healthURL = "http://127.0.0.1:1338/health"

func healthy() bool {
	client := http.Client{Timeout: 2 * time.Second}
	response, err := client.Get(healthURL)
	if err != nil {
		return false
	}
	defer response.Body.Close()
	_, _ = io.Copy(io.Discard, response.Body)
	return response.StatusCode == http.StatusOK
}

func dataDirectory() string {
	if configured := os.Getenv("CLOUD_ITONAMI_DATA_DIR"); configured != "" {
		return configured
	}
	if local := os.Getenv("LOCALAPPDATA"); local != "" {
		return filepath.Join(local, "Cloud Itonami")
	}
	config, _ := os.UserConfigDir()
	return filepath.Join(config, "Cloud Itonami")
}

func stopRecordedServer(dataDir string) {
	pidBytes, err := os.ReadFile(filepath.Join(dataDir, "server.pid"))
	if err != nil {
		return
	}
	pid := strings.TrimSpace(string(pidBytes))
	if _, err := strconv.Atoi(pid); err == nil {
		_ = exec.Command("taskkill.exe", "/PID", pid, "/T", "/F").Run()
	}
	_ = os.Remove(filepath.Join(dataDir, "server.pid"))
}

func startPendingUpdate(installDir, dataDir string) bool {
	pending := filepath.Join(dataDir, "updates", "pending")
	packagePath := filepath.Join(pending, "package.zip")
	marker := filepath.Join(pending, "pending.edn")
	if _, err := os.Stat(packagePath); err != nil {
		return false
	}
	if _, err := os.Stat(marker); err != nil {
		return false
	}
	stopRecordedServer(dataDir)
	helperSource := filepath.Join(installDir, "ApplyUpdateWindows.ps1")
	helperTarget := filepath.Join(dataDir, "updates", "ApplyUpdateWindows.ps1")
	contents, err := os.ReadFile(helperSource)
	if err != nil {
		return false
	}
	if err := os.WriteFile(helperTarget, contents, 0600); err != nil {
		return false
	}
	command := exec.Command("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
		"-File", helperTarget, "-InstallDir", installDir, "-Package", packagePath,
		"-PendingDir", pending, "-ParentPid", strconv.Itoa(os.Getpid()))
	if err := command.Start(); err != nil {
		return false
	}
	return true
}

func startServer(installDir, dataDir, logDir string) error {
	server := filepath.Join(installDir, "cloud-itonami-server")
	if _, err := os.Stat(server); err != nil {
		return fmt.Errorf("cloud-itonami-server is missing; this launcher does not start Java")
	}
	logFile, err := os.OpenFile(filepath.Join(logDir, "server.log"), os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0600)
	if err != nil {
		return err
	}
	command := exec.Command(server)
	command.Env = append(os.Environ(), "CLOUD_ITONAMI_DATA_DIR="+dataDir)
	command.Stdout = logFile
	command.Stderr = logFile
	if err := command.Start(); err != nil {
		_ = logFile.Close()
		return err
	}
	_ = os.WriteFile(filepath.Join(dataDir, "server.pid"), []byte(strconv.Itoa(command.Process.Pid)), 0600)
	return nil
}

func openWindow() error {
	if edge, err := exec.LookPath("msedge.exe"); err == nil {
		return exec.Command(edge, "--app=http://localhost:1338/", "--new-window", "--window-size=1100,760").Start()
	}
	return exec.Command("cmd.exe", "/c", "start", "", "http://localhost:1338/").Start()
}

// showStartupErrorDialog opens a modal message box so a Windows user who
// double-clicks the launcher sees WHY the window never appears. A log file
// alone is invisible: the 2026-09-03 v0.4.1 report showed a silent first
// launch where localhost:1338 never came up. PowerShell is used rather than
// a Go GUI dependency; user32.MessageBox via a compiled C# fallback is
// unnecessary — powershell.exe ships on every supported Windows.
func showStartupErrorDialog(logPath string) {
	title := "Cloud Itonami failed to start"
	message := "The Cloud Itonami server terminated during startup.\n\n" +
		"Details were written to:\n" + logPath
	script := fmt.Sprintf(
		"Add-Type -AssemblyName System.Windows.Forms; "+
			"[System.Windows.Forms.MessageBox]::Show(%q, %q, "+
			"'OK', 'IconError') | Out-Null", message, title)
	_ = exec.Command("powershell.exe", "-NoProfile",
		"-NonInteractive", "-Command", script).Start()
}

// A previous launch's error must not survive: a stale prerequisite warning
// left behind by an old install made a new failure look like the old one.
// Overwrite, never append.
func writeLauncherError(logDir, message string) {
	logPath := filepath.Join(logDir, "launcher-error.log")
	_ = os.WriteFile(logPath, []byte(message), 0600)
	showStartupErrorDialog(logPath)
}

func main() {
	executable, err := os.Executable()
	if err != nil {
		return
	}
	installDir := filepath.Dir(executable)
	dataDir := dataDirectory()
	logDir := filepath.Join(dataDir, "logs")
	_ = os.MkdirAll(logDir, 0700)
	if startPendingUpdate(installDir, dataDir) {
		return
	}
	if !healthy() {
		if err := startServer(installDir, dataDir, logDir); err != nil {
			writeLauncherError(logDir, err.Error())
			return
		}
		for attempt := 0; attempt < 240 && !healthy(); attempt++ {
			time.Sleep(250 * time.Millisecond)
		}
	}
	if healthy() {
		_ = openWindow()
	} else {
		showStartupErrorDialog(filepath.Join(logDir, "server.log"))
	}
}
