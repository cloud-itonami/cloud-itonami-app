#!/usr/bin/env hy
"Hand the itonami growth bots their measurements.

Decision-free. It runs `scripts/itonami-growth-evidence.cljs` against the
populated superproject and prints what that produced. Every rule about what
counts as a maturity candidate or a coverage gap lives in that nbb script, the
maturity tick it calls, and the UN mirrors those read; none of it is repeated
here. This wrapper is Hy so the Kotoba agent scheduler can run the bot's cron
entrypoint in Hy while leaving the established collector and its decisions
unchanged.

TWO ROOTS, ON PURPOSE.

  READ_ROOT   the real superproject. It is the only checkout where `orgs/` is
              populated, and the ISIC and COFOG mirrors live there. Read only.
  WORK_ROOT   a worktree of the same repository. The bot may branch and commit
              here. Nothing writes to READ_ROOT, because other sessions are
              working in it and AGENTS.md forbids it.

THE EXIT CODE IS LOAD-BEARING IN ONE DIRECTION.

Hermes injects this script's stdout into the agent's prompt. An empty or
half-written report reaches the model as \"there is nothing to raise\" -- which
is the one answer that must never be produced by failure, because this fleet's
whole measurement problem is that unmeasured and clean look alike. So on any
refusal this prints a REFUSED banner and still exits 0: the bot has to run and
be told it is blind, not be silently skipped.
"
(import os)
(import subprocess)
(import sys)
(setv READ_ROOT
  ((. os.environ get) "ITONAMI_READ_ROOT" ((. os.path expanduser) "~/github/com-junkawasaki")))

(defn #^ str _work_root []
  (setv explicit ((. os.environ get) "ITONAMI_BOT_WORKTREE"))
  (when explicit (return ((. os.path expanduser) explicit)))
  (setv cwd (os.getcwd))
  (when (and
          ((. os.path exists) ((. os.path join) cwd ".git"))
          (!=
            cwd
            ((. os.path abspath) ((. os.path expanduser) "~/github/com-junkawasaki"))))
    (return cwd))
  (return ((. os.path expanduser) "~/.gftd/worktrees/itonami-growth-bot")))

(setv WORK_ROOT (_work_root))
(setv NBB ((. os.environ get) "ITONAMI_NBB" "/opt/homebrew/bin/nbb"))
(setv CANDIDATES ((. os.environ get) "ITONAMI_CANDIDATES" "5"))

(defn #^ None refuse [#^ str why]
  (print "REFUSED — no evidence was gathered this run.")
  (print why)
  (print)
  (print "Do not propose anything. A maturity or coverage proposal built on an unread tree is a proposal built on nothing. Report this refusal and stop.")
  (sys.exit 0))

(defn git [root #* args]
  (return
    (subprocess.run (+ #("git" "-C" root) args)
      :capture_output True
      :text True
      :timeout 300)))

(defn #^ None main []
  (when (not
         ((. os.path isdir) ((. os.path join) READ_ROOT "orgs")))
   (refuse f"{READ_ROOT} has no populated orgs/ — the ISIC and COFOG mirrors cannot be read, and a gap list computed without them would be a list of everything"))
  (when (not
          ((. os.path exists) ((. os.path join) WORK_ROOT ".git")))
    (refuse f"no bot worktree at {WORK_ROOT} (see the README for how it is made)"))
  (when (!= (. (git WORK_ROOT "fetch" "--quiet" "origin") returncode) 0) (refuse "git fetch failed in the bot worktree"))
  (setv reset (git WORK_ROOT "checkout" "--quiet" "--detach" "origin/main"))
  (when (!= reset.returncode 0)
    (refuse f"could not move the bot worktree to origin/main: {(cut ((. reset.stderr strip)) 400)}"))
  (setv head ((. (. (git WORK_ROOT "rev-parse" "--short" "HEAD") stdout) strip)))
  (setv proc
    (subprocess.run [NBB "--classpath" f"{READ_ROOT}:{READ_ROOT}/scripts/nbb_compat" f"{READ_ROOT}/scripts/itonami-growth-evidence.cljs" "--root" READ_ROOT "--candidates" CANDIDATES]
      :cwd READ_ROOT
      :capture_output True
      :text True
      :timeout 900))
  (when (= proc.returncode 2)
    (refuse
      (+
        "the evidence collector refused:\n"
        (cut
          (or ((. proc.stdout strip)) ((. proc.stderr strip)))
          1200))))
  (when (!= proc.returncode 0)
    (refuse
      (+
        f"the evidence collector exited {proc.returncode}:\n"
        (cut
          (or ((. proc.stderr strip)) ((. proc.stdout strip)))
          1200))))
  (when (not-in "SCANNED\t" proc.stdout) (refuse "the collector produced no SCANNED line, so its output cannot be distinguished from a truncated run"))
  (print f"read-root\t{READ_ROOT}\t(read only)")
  (print f"work-root\t{WORK_ROOT}\thead {head}")
  (print proc.stdout))

(when (= __name__ "__main__") (main))
