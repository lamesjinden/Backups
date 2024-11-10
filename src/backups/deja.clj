(ns backups.deja
  (:require [backups.core :as core]
            [backups.console :as console]
            [cats.context :as cats-ctx]
            [cats.core :as cats]
            [cats.monad.either :as either]
            [cats.monad.exception :as cats-ex]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [scheduling.shutdown :as vshutdown]
            [scheduling.startup :as vstartup]
            [scheduling.vbox :as vbox]))

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

(s/def ::prune-args (s/+ string?))
(s/def ::prune-destination ::duplicity-destination)
(s/def ::prune-config (s/keys :req [::prune-args
                                    ::prune-destination]))

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
                                   ::prune-config
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
      (either/right nil))
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
      (either/right nil))
    (mf)))

(defn- try-conform [spec x]
  (let [conformed (s/conform spec x)]
    (if (= conformed :clojure.spec.alpha/invalid)
      (throw (ex-info (s/explain-str spec x)
                      (s/explain-data spec x)))
      conformed)))

(defn- try-conform-m [spec x]
  (cats-ex/try-on (try-conform spec x)))

; endregion

; region duplicity

;; "--dry-run"
(def duplicity-default-args ["--no-encryption"
                             "--full-if-older-than 1Y"
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

(def duplicity-prune-default-args ["remove-all-but-n-full 1"
                                   "--force"])

(defn ->duplicity-prune-command [prune-config]
  (let [conformed (try-conform ::prune-config prune-config)
        {:keys [::prune-args
                ::prune-destination]} conformed
        prune-args (str/join " " prune-args)
        command (format "duplicity %s %s" prune-args prune-destination)]
    command))

(defn run-duplicity-prune-backups! [prune-config]
  (let [command (->duplicity-prune-command prune-config)]
    (backups.core/run-process command)))

(defn run-duplicity-prune-backups-m! [prune-config]
  (let [result (run-duplicity-prune-backups! prune-config)]
    (backups.core/sh->either result)))

(defn prune-managed-backups-m! [prune-config]
  (log-header "Pruning existing managed backups")

  (when-not-nil-m
   prune-config
   (fn [] (cats/do-let (try-conform-m ::prune-config prune-config)
                       (let [{:keys [::prune-destination]} prune-config]
                         (when-not-empty-m
                          prune-destination
                          (fn [] (run-duplicity-prune-backups-m! prune-config))))))))

; endregion

; region rsync

;; "--dry-run"
(def rsync-default-args ["-avuz"
                         "--delete"
                         "--progress"
                         "--timeout=120"])

(defn start-vm! [vm]
  (vstartup/fancy-startup vm))

(defn start-vm-m! [vm]
  (either/try-either (start-vm! vm)))

(defn start-vms-m! [vms]
  (cats-ctx/with-context cats-ex/context
    (cats/mlet [_start-results (cats/mapseq start-vm-m! vms)]
               (either/right vms))))

(defn stop-running-vm! [vm]
  (vshutdown/fancy-shutdown vm))

(defn stop-running-vm-m! [vm]
  (either/try-either (stop-running-vm! vm)))

(defn stop-running-vms-m! []
  (let [running-vms (->> (vbox/get-all-running-vms)
                         (map (fn [{:keys [name] :as m}]
                                (assoc m :start-type (or (vbox/get-running-vm-type name)
                                                         :headless)))))]
    (cats-ctx/with-context cats-ex/context
      (cats/mlet [_stop-results (cats/mapseq stop-running-vm-m! running-vms)]
                 (either/right running-vms)))))

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
                     (fn [] (cats/mlet [stopped-vms (stop-running-vms-m!)
                                        _rsync-backups (run-rsync-backups-m! rsync-config rsync-source-dirs)]
                                       (start-vms-m! stopped-vms)))))))))

; endregion

; region main

(defn run [config]
  (let [conformed (try-conform ::deja-config config)
        {:keys [::duplicity-config
                ::prune-config
                ::rsync-config
                ::start-fn
                ::result-fn]
         :or {start-fn backups.core/print-start-message
              result-fn backups.core/handle-result}} conformed]
    (start-fn)
    (let [result (cats/do-let
                  (prune-managed-backups-m! prune-config)
                  (take-managed-backup-m! duplicity-config)
                  (take-unmanaged-backup-m! rsync-config))]
      (result-fn result))))

; endregion
