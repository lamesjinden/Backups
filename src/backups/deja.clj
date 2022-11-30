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

(def ^:dynamic *logging-enabled?* false)

(defn log-header [msg]
  (console/println-blue msg))

(defn log [msg]
  (when *logging-enabled?*
    (println msg)))

; todo
;   * mount the backup share via `gio mount` to avoid need to execute as root
;   * wrap use of try-conform with cats.monad.exception/try

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
(s/def ::deja-config (s/keys :req [::mount-config]
                             :opt [::duplicity-config
                                   ::rsync-config
                                   ::start-fn
                                   ::result-fn]))

(defn- try-conform [spec x]
  (let [conformed (s/conform spec x)]
    (if (= conformed :clojure.spec.alpha/invalid)
      (throw (ex-info (s/explain-str ::spec x)
                      (s/explain-data ::spec x)))
      conformed)))

; region mounting

(defn ->mount-command [config]
  (let [conformed (try-conform ::mount-config config)
        {:keys [::share-url ::share-mount ::id]} conformed
        {:keys [::username ::domain ::userid ::groupid]} id
        command (format "mount -t cifs -o username=%s,dom=%s,uid=%s,gid=%s %s %s"
                        username
                        domain
                        userid
                        groupid
                        share-url
                        share-mount)]
    command))

(defn run-mount-backups! [config]
  (let [command (->mount-command config)]
    ; note: using run-sh caused a delay (longer than 60 seconds), awaiting to be prompted for cifs credentials.
    ;       switching to run-process does not display the above behavior.
    (core/run-process command)))

(defn run-mount-backups-m! [config]
  (let [result (run-mount-backups! config)]
    (backups.core/sh->either result [0 32])))

(defn mount-backups-share-m! [config]
  (log-header "Mounting Backups share")

  (run-mount-backups-m! config))

; endregion

; region duplicity

;; "--dry-run"
(def duplicity-default-args ["--no-encryption"
                             "--full-if-older-than 60D"
                             "--volsize 100"
                             "--timeout=120"
                             "--num-retries 5"
                             "--asynchronous-upload"
                             "--verbosity=9"
                             "--rsync-options=\"--progress\""])

(defn ->include [include]
  (format "--include '%s'" include))

(defn ->exclude [exclude]
  (format "--exclude '%s'" exclude))

(defn ->duplicity-command [config]
  (let [conformed (try-conform ::duplicity-config config)
        {:keys [::duplicity-args
                ::duplicity-include-dirs
                ::duplicity-exclude-dirs
                ::duplicity-destination]} conformed
        duplicity-args (str/join " " duplicity-args)
        gio? (or (str/starts-with? duplicity-destination "smb://") "")
        includes (->> duplicity-include-dirs
                      (map ->include)
                      (str/join " "))
        excludes (->> duplicity-exclude-dirs
                      (map ->exclude)
                      (str/join " "))
        command (format "duplicity %s %s %s %s --exclude '**' / '%s'"
                        duplicity-args
                        (if gio? "--gio" "")
                        includes
                        excludes
                        duplicity-destination)]
    command))

(defn run-duplicity-backup! [config]
  (let [command (->duplicity-command config)]
    (backups.core/run-process command)))

(defn run-duplicity-backup-m! [config]
  (let [result (run-duplicity-backup! config)]
    (backups.core/sh->either result)))

(defn take-managed-backup-m! [config]
  (log-header "Taking managed backup")

  (let [conformed (try-conform ::duplicity-config config)
        {:keys [::duplicity-include-dirs]} conformed]
    (if (not-empty duplicity-include-dirs)
      (run-duplicity-backup-m! config)
      (do
        (log "nothing to do")
        (cats-either/right config)))))

; endregion

; region rsync

;; "--dry-run"
(def rsync-default-args ["-avuz" "--delete --progress"])

(defn stop-running-vm! [vm]
  (vshutdown/fancy-shutdown vm))

(defn stop-running-vm-m! [vm]
  (cats-either/try-either (stop-running-vm! vm)))

(defn stop-running-vms-m! []
  (let [running-vms (->> (vbox/get-all-running-vms)
                         ; todo - remove filter
                         (filter #(not= (:name %) "Dev")))]
    (cats-ctx/with-context cats-ex/context
      (cats/mapseq stop-running-vm-m! running-vms))))

(defn ->rsync-command [config rsync-source-dir]
  (let [conformed (try-conform ::rsync-config config)
        {:keys [::rsync-args
                ::rsync-destination]} conformed
        args (str/join " " rsync-args)
        source rsync-source-dir
        destination rsync-destination
        command (format "rsync %s '%s' '%s'" args source destination)]
    command))

(defn run-rsync-backup! [config rsync-source-dir]
  (let [command (->rsync-command config rsync-source-dir)]
    (backups.core/run-process command)))

(defn run-rsync-backup-m! [config rsync-source-dir]
  (let [result (run-rsync-backup! config rsync-source-dir)]
    (backups.core/sh->either result)))

(defn take-unmanaged-backup-m! [config]
  (log-header "Taking unmanaged backup")

  (let [conformed (try-conform ::rsync-config config)
        {:keys [::rsync-source-dirs]} conformed]
    (if (not-empty rsync-source-dirs)
      (cats/do-let (stop-running-vms-m!)
                   (cats-ctx/with-context cats-ex/context
                     (cats/mapseq #(run-rsync-backup-m! config %) rsync-source-dirs)))
      (do
        (log "  nothing to do")
        (cats-either/right config)))))

; endregion

; region unmount

(defn ->unmount-command [config]
  (let [conformed (try-conform ::mount-config config)
        {:keys [::share-mount]} conformed]
    (format "umount %s" share-mount)))

(defn run-unmount-backups-share! [config]
  (let [command (->unmount-command config)]
    (backups.core/run-sh command)))

(defn run-unmount-backups-share-m! [config]
  (let [result (run-unmount-backups-share! config)]
    (backups.core/sh->either result)))

(defn unmount-backups-share-m! [config]
  (log-header "Unmounting Backups share")

  (run-unmount-backups-share-m! config))

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
