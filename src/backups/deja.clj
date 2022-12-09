(ns backups.deja
  (:require [backups.core :as core]
            [backups.console :as console]
            [cats.context :as cats-ctx]
            [cats.core :as cats]
            [cats.monad.either :as cats-either]
            [cats.monad.exception :as cats-ex]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [scheduling.shutdown :as vshutdown]
            [scheduling.vbox :as vbox]))

; todo
;   * mount the backup share via `gio mount` to avoid need to execute as root
;   * rsync instead of cifs mount?
;     * prompt for each rsync source directory?
;     * could mounting on the host create a symlink for /mnt/backups that points to the current drive?
;   * wrap use of try-conform with cats.monad.exception/try

(def ^:dynamic *logging-enabled?* false)

(s/def ::username string?)
(s/def ::userid string?)
(s/def ::groupid string?)
(s/def ::domain string?)
(s/def ::id (s/keys :req [::username ::userid ::groupid ::domain]))

(s/def ::share-url string?)
(s/def ::share-mount string?)
(s/def ::mount-config (s/keys :req [::share-url ::share-mount ::id]))

(s/def ::duplicity-dirs (s/* string?))
(s/def ::duplicity-include-dirs ::duplicity-dirs)
(s/def ::duplicity-exclude-dirs ::duplicity-dirs)
(s/def ::duplicity-destination ::share-mount)
(s/def ::duplicity-args (s/+ string?))
(s/def ::duplicity-config (s/keys :req [::duplicity-args
                                        ::duplicity-include-dirs
                                        ::duplicity-exclude-dirs
                                        ::duplicity-destination]))

(s/def ::rsync-source-dir (s/and string? #(not (str/ends-with? % "/"))))
(s/def ::rsync-source-dirs (s/* ::rsync-source-dir))
(s/def ::rsync-destination string?)
(s/def ::rsync-args (s/* string?))
(s/def ::rsync-config (s/keys :req [::rsync-args
                                    ::rsync-source-dirs
                                    ::rsync-destination]))

(s/def ::start-fn fn?)
(s/def ::result-fn fn?)
(s/def ::deja-config (s/keys :opt [::mount-config
                                   ::duplicity-config
                                   ::rsync-config
                                   ::start-fn
                                   ::result-fn]))

(defn- log-header [msg]
  (console/println-blue msg))

(defn- log [msg]
  (when *logging-enabled?*
    (println msg)))

(defn- log-noop []
  (log "  nothing to do"))

(defn- log-skipped []
  (log "  skipped"))

(defn- when-not-nil-m
  "
   when config is not nil, invokes mf, a monad-returning function.
   otherwise returns either/right of nil and logs
   "
  [config mf]
  (if (nil? config)
    (do
      (log-skipped)
      (cats-either/right nil))
    (mf)))

(defn- when-not-empty-m
  "
   when coll is not empty, invokes mf, a monad-returning function.
   otherwise returns either/rigth of nil and logs.
   "
  [coll mf]
  (if (empty? coll)
    (do
      (log-noop)
      (cats-either/right nil))
    (mf)))

(defn- try-conform [spec x]
  (let [conformed (s/conform spec x)]
    (if (= conformed :clojure.spec.alpha/invalid)
      (throw (ex-info (s/explain-str spec x)
                      (s/explain-data spec x)))
      conformed)))

(defn- try-conform-m [spec x]
  (cats-ex/try-on (try-conform spec x)))

; region mounting

(defn ->mount-command
  "
  returns a valid `mount` command for a cifs mount.
  throws if mount-config does not conform.
  "
  [mount-config]
  (let [conformed (try-conform ::mount-config mount-config)
        {:keys [::share-url ::share-mount ::id]} conformed
        {:keys [::username ::domain ::userid ::groupid]} id
        command (format "sudo mount -t cifs -o username=%s,dom=%s,uid=%s,gid=%s %s %s"
                        username
                        domain
                        userid
                        groupid
                        share-url
                        share-mount)]
    command))

(defn run-mount-backups! [mount-config]
  (let [command (->mount-command mount-config)]
    ; note: using run-sh caused a delay (longer than 60 seconds), awaiting to be prompted for cifs credentials.
    ;       switching to run-process does not display the above behavior.
    (core/run-process command)))

(defn run-mount-backups-m! [mount-config]
  (let [result (run-mount-backups! mount-config)]
    (backups.core/sh->either result [0 32])))

(defn mount-backups-share-m! [mount-config]
  (log-header "Mounting Backups share")

  (when-not-nil-m
   mount-config
   (fn [] (cats/do-let (try-conform-m ::mount-config mount-config)
                       (run-mount-backups-m! mount-config)))))

; endregion

; region duplicity

;; "--dry-run"
(def duplicity-default-args ["--no-encryption"
                             "--full-if-older-than 60D"
                             "--volsize 1000"
                             "--timeout=120"
                             "--num-retries 5"
                             "--asynchronous-upload"
                             "--verbosity=8"])

(defn ->include [include]
  (format "--include '%s'" include))

(defn ->exclude [exclude]
  (format "--exclude '%s'" exclude))

(defn ->duplicity-command
  "
  creates a valid `duplicity` command.
  throws if duplicity-config does not conform.
  "
  [duplicity-config]
  (let [conformed (try-conform ::duplicity-config duplicity-config)
        {:keys [::duplicity-args
                ::duplicity-include-dirs
                ::duplicity-exclude-dirs
                ::duplicity-destination]} conformed
        duplicity-args (str/join " " duplicity-args)
        gio? (str/starts-with? duplicity-destination "smb://")
        includes (->> duplicity-include-dirs
                      (map ->include)
                      (str/join " "))
        excludes (->> duplicity-exclude-dirs
                      (map ->exclude)
                      (str/join " "))
        command (format "duplicity %s %s %s %s --exclude '**' / '%s'"
                        duplicity-args
                        (if gio? "--gio" "")
                        ;; excludes before includes gives excludes precedence.
                        ;; the assumption is that the include would be /home/userA
                        ;; with excludes of /home/userA/Downloads, /home/userA/VirtualBox\ VMs
                        ;; then, duplicity would backup all of /home/userA except but skip the excludes
                        excludes
                        includes
                        duplicity-destination)]
    command))

(defn run-duplicity-backup! [duplicity-config]
  (let [command (->duplicity-command duplicity-config)]
    (backups.core/run-process command)))

(defn run-duplicity-backup-m! [duplicity-config]
  (let [result (run-duplicity-backup! duplicity-config)]
    (backups.core/sh->either result)))

(defn take-managed-backup-m! [duplicity-config]
  (log-header "Taking managed backup")

  (when-not-nil-m
   duplicity-config
   (fn [] (cats/do-let (try-conform-m ::duplicity-config duplicity-config)
                       (let [{:keys [::duplicity-include-dirs]} duplicity-config]
                         (when-not-empty-m
                          duplicity-include-dirs
                          (fn [] (run-duplicity-backup-m! duplicity-config))))))))

; endregion

; region rsync

;; "--dry-run"
(def rsync-default-args ["-avuz" 
                         "--delete" 
                         "--progress"
                         "--timeout=120"])

(defn stop-running-vm! [vm]
  (vshutdown/fancy-shutdown vm))

(defn stop-running-vm-m! [vm]
  (cats-either/try-either (stop-running-vm! vm)))

(defn stop-running-vms-m! []
  (let [running-vms (vbox/get-all-running-vms)]
    (cats-ctx/with-context cats-ex/context
      (cats/mapseq stop-running-vm-m! running-vms))))

(defn ->rsync-command [rsync-config rsync-source-dir]
  (let [conformed (try-conform ::rsync-config rsync-config)
        {:keys [::rsync-args
                ::rsync-destination]} conformed
        args (str/join " " rsync-args)
        source rsync-source-dir
        destination rsync-destination
        command (format "rsync %s '%s' '%s'" args source destination)]
    command))

(defn run-rsync-backup! [rsync-config rsync-source-dir]
  (let [command (->rsync-command rsync-config rsync-source-dir)]
    (backups.core/run-process command)))

(defn run-rsync-backup-m! [rsync-config rsync-source-dir]
  (let [result (run-rsync-backup! rsync-config rsync-source-dir)]
    (backups.core/sh->either result)))

(defn run-rsync-backups-m! [rsync-config rsync-source-dirs]
  (cats-ctx/with-context cats-ex/context
    (cats/mapseq #(run-rsync-backup-m! rsync-config %) rsync-source-dirs)))

(defn take-unmanaged-backup-m! [rsync-config]
  (log-header "Taking unmanaged backup")

  (when-not-nil-m
   rsync-config
   (fn []
     (cats/do-let (try-conform-m ::rsync-config rsync-config)
                  (let [{:keys [::rsync-source-dirs]} rsync-config]
                    (when-not-empty-m
                     rsync-source-dirs
                     (fn [] (cats/do-let (stop-running-vms-m!)
                                         (run-rsync-backups-m! rsync-config rsync-source-dirs)))))))))

; endregion

; region unmount

(defn ->unmount-command
  "
  returns a valid `umount` command for unmounting the cifs share.
  throws if mount-config does not conform.
  "
  [mount-config]
  (let [conformed (try-conform ::mount-config mount-config)
        {:keys [::share-mount]} conformed]
    (format "sudo umount %s" share-mount)))

(defn run-unmount-backups-share! [mount-config]
  (let [command (->unmount-command mount-config)]
    (backups.core/run-sh command)))

(defn run-unmount-backups-share-m! [mount-config]
  (let [result (run-unmount-backups-share! mount-config)]
    (backups.core/sh->either result)))

(defn unmount-backups-share-m! [mount-config]
  (log-header "Unmounting Backups share")

  (when-not-nil-m
   mount-config
   (fn [] (cats/do-let (try-conform-m ::mount-config mount-config)
                       (run-unmount-backups-share-m! mount-config)))))

; endregion

; region main

(defn run [config]
  (let [conformed (try-conform ::deja-config config)
        {:keys [::mount-config
                ::duplicity-config
                ::rsync-config
                ::start-fn
                ::result-fn]
         :or {start-fn backups.core/print-start-message
              result-fn backups.core/handle-result}} conformed]
    (start-fn)
    (let [result (cats/do-let (mount-backups-share-m! mount-config)
                              (take-managed-backup-m! duplicity-config)
                              (take-unmanaged-backup-m! rsync-config)
                              (unmount-backups-share-m! mount-config))]
      (result-fn result))))

; endregion
