;; Cross-platform owner-only file protection.
;;
;; The 2026-09-03 v0.4.1 Windows report: first launch died with
;; UnsupportedOperationException "'posix:permissions' not supported as
;; initial attribute" from sun.nio.fs.WindowsSecurityDescriptor, because
;; every startup-path file was created or hardened with
;; PosixFilePermissions — an API the Windows filesystem provider does not
;; implement. http://localhost:1338 never came up.
;;
;; This namespace is the single call site for "this file must be readable
;; only by its owner". On POSIX filesystems the semantics are the existing
;; rw-------/rwx------. On Windows (NTFS) the equivalent is a restrictive
;; ACL applied through AclFileAttributeView: the owner keeps full control,
;; SYSTEM and Administrators keep their standard access (as on POSIX where
;; root always reads), and every other principal — Users, Everyone, the
;; inherited entries — is removed. It is NOT merely a chmod-equivalent
;; no-op: a fresh NTFS file normally inherits a broad Users::READ ACL.
;;
;; Detection is by FileStore capability, not os.name: an SMB/exFAT mount on
;; Windows supports neither view, a POSIX-ish filesystem on macOS does. If
;; neither view is supported the call is a best-effort no-op, matching the
;; pre-existing `catch UnsupportedOperationException _ nil` behavior at
;; every former call site.
(ns cloud.itonami.app.secure-file
  (:import [java.io File]
           [java.nio.file Files FileStore LinkOption Path]
           [java.nio.file.attribute
            AclEntry AclEntry$Builder AclEntryFlag AclEntryPermission AclEntryType
            AclFileAttributeView FileAttribute PosixFileAttributeView
            PosixFilePermission PosixFilePermissions
            UserPrincipal]))

(defn- ^FileStore file-store ^FileStore [^Path path]
  (or (try
        (Files/getFileStore path)
        (catch Exception _ nil))
      ;; A not-yet-created file has no FileStore of its own; the parent
      ;; directory reports the same filesystem's capabilities.
      (try
        (Files/getFileStore (.getParent path))
        (catch Exception _ nil))))

(defn- posix-supported? [^Path path]
  (when-let [store (file-store path)]
    (try
      (.supportsFileAttributeView store PosixFileAttributeView)
      (catch Exception _ false))))

(defn- acl-supported? [^Path path]
  (when-let [store (file-store path)]
    (try
      (.supportsFileAttributeView store AclFileAttributeView)
      (catch Exception _ false))))

(defn- posix-permission-set [mode]
  (PosixFilePermissions/fromString mode))

(defn- apply-posix! [^Path path mode]
  (Files/setPosixFilePermissions path (posix-permission-set mode)))

(def ^:private acl-owner-permissions
  #{AclEntryPermission/READ_DATA AclEntryPermission/WRITE_DATA
    AclEntryPermission/APPEND_DATA AclEntryPermission/READ_ATTRIBUTES
    AclEntryPermission/WRITE_ATTRIBUTES AclEntryPermission/READ_NAMED_ATTRS
    AclEntryPermission/WRITE_NAMED_ATTRS AclEntryPermission/DELETE
    AclEntryPermission/DELETE_CHILD AclEntryPermission/READ_ACL
    AclEntryPermission/WRITE_ACL AclEntryPermission/WRITE_OWNER
    AclEntryPermission/EXECUTE AclEntryPermission/SYNCHRONIZE})

(def ^:private acl-system-permissions
  #{AclEntryPermission/READ_DATA AclEntryPermission/WRITE_DATA
    AclEntryPermission/APPEND_DATA AclEntryPermission/READ_ATTRIBUTES
    AclEntryPermission/WRITE_ATTRIBUTES AclEntryPermission/READ_NAMED_ATTRS
    AclEntryPermission/WRITE_NAMED_ATTRS AclEntryPermission/DELETE
    AclEntryPermission/READ_ACL AclEntryPermission/WRITE_ACL
    AclEntryPermission/EXECUTE AclEntryPermission/SYNCHRONIZE})

(defn- acl-entry [^UserPrincipal principal permissions flags]
  (doto (AclEntry/newBuilder)
    (.setType AclEntryType/ALLOW)
    (.setPrincipal principal)
    (.setPermissions permissions)
    (.setFlags flags)))

(defn- apply-acl! [^Path path]
  (let [view (Files/getFileAttributeView path AclFileAttributeView
                                         (into-array LinkOption []))
        owner (.getOwner view)
        lookup (.getUserPrincipalLookupService (.getFileSystem view))
        ;; Administrators / SYSTEM resolve only on Windows. On a
        ;; filesystem that advertises ACL support but cannot resolve
        ;; these well-known SIDs, keep owner + the resolved ones.
        resolved (remove nil?
                         (map (fn [name]
                                (try
                                  (.lookupPrincipalByName lookup name)
                                  (catch Exception _ nil)))
                              ["SYSTEM" "Administrators"]))
        ;; DIRECTORIES need the entries to propagate; files do not.
        directory? (Files/isDirectory path (into-array LinkOption []))
        inherit-flags (if directory?
                        #{AclEntryFlag/DIRECTORY_INHERIT
                          AclEntryFlag/FILE_INHERIT}
                        #{})
        owner-entry (acl-entry owner acl-owner-permissions inherit-flags)
        admin-entries (map #(acl-entry % acl-system-permissions inherit-flags)
                           resolved)]
    (.setAcl view (into java.util.ArrayList
                        (cons owner-entry admin-entries)))))

(defn harden!
  "Restrict an EXISTING file (or directory) to owner-only access.
  POSIX: chmod-equivalent to `mode` (e.g. \"rw-------\"). Windows/NTFS:
  restrictive ACL — owner full control, SYSTEM/Administrators standard
  access, no Users/Everyone. Returns the path argument for threading.
  Never throws: unsupported filesystems are a silent no-op, matching the
  pre-fix behavior at every call site."
  ([file-or-path] (harden! file-or-path "rw-------"))
  ([file-or-path mode]
   (try
     (let [path (if (instance? File file-or-path)
                  (.toPath ^File file-or-path)
                  ^Path file-or-path)]
       (cond
         (posix-supported? path) (apply-posix! path mode)
         (acl-supported? path) (apply-acl! path)
         :else nil))
     (catch Exception _ nil))
   file-or-path))

(defn- create-attrs [path mode]
  (cond
    (posix-supported? path)
    (into-array FileAttribute
                [(PosixFilePermissions/asFileAttribute
                  (posix-permission-set mode))])
    :else (make-array FileAttribute 0)))

(defn create-file!
  "Create an empty file at `path` (java.io.File or Path) with owner-only
  permissions applied at creation time. On POSIX the restrictive mode is
  passed as the initial attribute (no window where the file is
  world-readable); on Windows the ACL is applied immediately after the
  file exists — NTFS has no create-with-ACL attribute, so the file
  inherits the parent ACL for the microseconds between create and
  harden, which is why the parent directory should itself be hardened.
  Fails with the underlying IOException when the file already exists.
  Never returns nil; rethrows non-permission-related failures."
  [file-or-path]
  (let [path (if (instance? File file-or-path)
               (.toPath ^File file-or-path)
               ^Path file-or-path)
        attrs (create-attrs path "rw-------")]
    (Files/createFile path attrs)
    (when-not (posix-supported? path)
      (harden! path "rw-------"))
    path))
