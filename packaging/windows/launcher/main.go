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
	java, err := exec.LookPath("java.exe")
	if err != nil {
		return fmt.Errorf("Java 21 or later is required")
	}
	logFile, err := os.OpenFile(filepath.Join(logDir, "server.log"), os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0600)
	if err != nil {
		return err
	}
	command := exec.Command(java,
		"-Dcloud.itonami.data-dir="+dataDir,
		"-cp", filepath.Join(installDir, "cloud-itonami-app.jar"),
		"clojure.main", "-m", "cloud.itonami.app.server")
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
		return exec.Command(edge, "--app=http://localhost:1338/", "--new-window", "--window-size=430,860").Start()
	}
	return exec.Command("cmd.exe", "/c", "start", "", "http://localhost:1338/").Start()
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
			_ = os.WriteFile(filepath.Join(logDir, "launcher-error.log"), []byte(err.Error()), 0600)
			return
		}
		for attempt := 0; attempt < 240 && !healthy(); attempt++ {
			time.Sleep(250 * time.Millisecond)
		}
	}
	if healthy() {
		_ = openWindow()
	}
}
