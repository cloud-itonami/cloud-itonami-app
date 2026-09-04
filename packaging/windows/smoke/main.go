// CI-only stand-in for the packaged `cloud-itonami-server` binary.
//
// The shipped Windows server binary is produced outside this repo's build
// scripts; this smoke test does not depend on it. The shim runs the SAME
// production path the real binary does — the JVM on the uberjar — which is
// exactly the surface where the 2026-09-03 Windows crash fired
// (sun.nio.fs.WindowsSecurityDescriptor / 'posix:permissions' on
// agent-enrollment.key creation at first boot). Not shipped.
package main

import (
	"os"
	"os/exec"
	"path/filepath"
)

func main() {
	exe, err := os.Executable()
	if err != nil {
		os.Exit(1)
	}
	jar := filepath.Join(filepath.Dir(exe), "cloud-itonami-app.jar")
	cmd := exec.Command("java", "-cp", jar, "clojure.main", "-m", "cloud.itonami.app.server")
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	if err := cmd.Run(); err != nil {
		os.Exit(1)
	}
}
