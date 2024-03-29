(ns backups.core
  (:require [babashka.process :as p]
            [backups.console :as console]
            [cats.core :as cats]
            [cats.monad.either :as either]
            [cats.monad.maybe :as maybe]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]))

; region defaults

(def default-share-name "backups")

(def ^:dynamic *log-commands?* false)

; endregion

; region shell

(defn print-start-message
  ([file-name] (console/println-green (format "%s: Executing" file-name)))
  ([] (print-start-message *file*)))

(defn run-sh [command-str]
  (let [command-tokens (p/tokenize command-str)]
    (when *log-commands?*
      (println command-tokens))
    (apply sh command-tokens)))

(defn sh->either
  "
  Returns sh-result wrapped in an instance of 'either'.
  When sh-result has an 'exit' code contained in accepted-exit-codes, returns 'either/right';
  otherwise, returns 'either/left'

  By default, non-zero exit-codes are returned as 'either/left'
  "
  ([{:keys [exit] :as sh-result} accepted-exit-codes]
   (let [accepted (into #{} accepted-exit-codes)]
     (if (contains? accepted exit)
       (either/right sh-result)
       (either/left sh-result))))
  ([sh-result]
   (sh->either sh-result #{0})))

(defn sh-result->out-m [sh-result]
  (-> sh-result
      (:out)
      (cats/return)))

(defn run-process [command]
  (let [options {:continue true}
        shell-result (p/shell options command)]
    (when *log-commands?*
      (println command))
    (-> shell-result
        (update :out (fn [prev] (slurp prev)))
        (update :err (fn [prev] (slurp prev))))))

; endregion

; region unlocking/locking

(defn run-list-volumes []
  (let [command-str (format "ls -l /dev/disk/by-uuid")]
    (run-sh command-str)))

(defn run-list-volumes-m []
  (sh->either (run-list-volumes)))

(defn validate-volume-id-m [volume-id out-volume-info]
  (if (str/includes? out-volume-info volume-id)
    (either/right true)
    (either/left (format "Failed to find UUID %s" volume-id))))

(defn validate-volume-m! [volume-id]
  (cats/>>= (->> (run-list-volumes-m)
                 (cats/left-map (fn [_] "Failed to list volumes by UUID")))
            sh-result->out-m
            (partial validate-volume-id-m volume-id)))

(defn run-volume-info [volume-id]
  (let [command-str (format "udisksctl info --block-device /dev/disk/by-uuid/%s" volume-id)]
    (run-sh command-str)))

(defn run-volume-info-m [volume-id]
  (sh->either (run-volume-info volume-id)))

(defn locked-m? [out-volume-info]
  (let [clear-text-device-pattern #"^\s+CleartextDevice:\s+([^\s]+).*$"
        clear-text-device-match (->> out-volume-info
                                     (str/split-lines)
                                     (map #(re-matches clear-text-device-pattern %))
                                     (filter #(not (nil? %)))
                                     (first))]
    (if (nil? clear-text-device-match)
      (maybe/nothing)
      (let [clear-text-device-value (clear-text-device-match 1)]
        (maybe/just (= clear-text-device-value "'/'"))))))

(defn run-unlock-volume [volume-id]
  (let [command-str (format "udisksctl unlock --block-device /dev/disk/by-uuid/%s" volume-id)]
    (run-sh command-str)))

(defn run-unlock-volume-m [volume-id]
  (-> volume-id
      (run-unlock-volume)
      (sh->either)))

(defn unlock-volume-m! [volume-id]
  (cats/>>=
   (cats/do-let (validate-volume-m! volume-id)
                (run-volume-info-m volume-id))
   sh-result->out-m
   locked-m?
   (fn [locked]
     (if locked
       (run-unlock-volume-m volume-id)
       (either/right volume-id)))))

(defn run-lock-volume [volume-id]
  (let [command-str (format "udisksctl lock --block-device /dev/disk/by-uuid/%s" volume-id)]
    (run-sh command-str)))

(defn run-lock-volume-m [volume-id]
  (sh->either (run-lock-volume volume-id)))

(defn lock-volume-m! [volume-id]
  (cats/>>= (run-volume-info-m volume-id)
            sh-result->out-m
            locked-m?
            (fn [locked]
              (if locked
                (either/right volume-id)
                (run-lock-volume-m volume-id)))))

; endregion

; region mounting/unmounting

(defn id->mapper-path [volume-id]
  (format "/dev/mapper/luks-%s" volume-id))

(defn run-mount-info []
  (let [command-str "mount"]
    (run-sh command-str)))

(defn run-mount-info-m []
  (sh->either (run-mount-info)))

(defn mounted? [volume-id mount-point out-mount-info]
  (some?
   (seq (->> (str/split-lines out-mount-info)
             (filter #(and (str/includes? % volume-id)
                           (str/includes? % mount-point)))))))

(defn mounted?-m [volume-id mount-point out-mount-info]
  (cats/return (mounted? volume-id mount-point out-mount-info)))

(defn run-mount-volume [volume-id mount-point]
  (let [command-str (format "mount %s %s" (id->mapper-path volume-id) mount-point)]
    (run-sh command-str)))

(defn run-mount-volume-m [volume-id mount-point]
  (sh->either (run-mount-volume volume-id mount-point)))

(defn run-create-backups-symlink! [target link-name]
  (let [link-name (format "/mnt/%s" link-name)
	command-str (format "ln -sfn '%s' '%s'" target link-name)]
    (run-sh command-str)))

(defn run-create-backups-symlink-m! [mount-point link-name]
  (sh->either (run-create-backups-symlink! mount-point link-name)))

(defn mount-volume-m! [volume-id mount-point]
  (cats/>>= (run-mount-info-m)
            sh-result->out-m
            (partial mounted?-m volume-id mount-point)
            (fn [mounted]
              (if (not mounted)
                (run-mount-volume-m volume-id mount-point)
                (either/right 0)))
            (fn [_] 
              (run-create-backups-symlink-m! mount-point default-share-name))))

(defn parse-mount-description-m
  [volume-id mount-point out-mount-info]
  (let [mount-line (->> (str/split-lines out-mount-info)
                        (filter #(and (str/includes? % volume-id) (str/includes? % mount-point)))
                        (first))]
    (if (some? mount-line)
      (either/right mount-line)
      (either/right nil))))

(defn run-list-mapper [volume-id]
  (let [command-str (format "ls %s" (id->mapper-path volume-id))]
    (run-sh command-str)))

(defn run-list-mapper-m
  "
  Projects/wraps the result of run-list-mapper into an instance of 'either'.
  Special handling for exit code 2, returned as either/right.
  Other non-zero exit code results are returned as either/left.
  "
  [volume-id]
  (let [exit-success 0
        exit-not-found 2]
    (-> volume-id
        (run-list-mapper)
        (sh->either #{exit-success exit-not-found}))))

(defn run-unmount-volume [mount-point]
  (let [command-str (format "umount %s" mount-point)]
    (run-sh command-str)))

(defn run-unmount-volume-m [mount-point]
  (sh->either (run-unmount-volume mount-point)))

(defn unmount-volume-m! [volume-id mount-point]
  (cats/>>
    ; note: order matters - `umount /dev/mapper/alias` before `umount mount-point`
   (cats/>>= (run-list-mapper-m volume-id)
             (fn [v] (sh->either v #{0 2}))
             (fn [v]
               (if (zero? (:exit v))
                 (run-unmount-volume-m (id->mapper-path volume-id))
                 (cats/return v))))
   (cats/>>= (run-mount-info-m)
             sh-result->out-m
             (partial parse-mount-description-m volume-id mount-point)
             (fn [v]
               (if (nil? v)
                 (cats/return v)
                 (run-unmount-volume-m mount-point))))))

; endregion

; region sharing/unsharing

(defn run-share-info []
  (let [command-str "net usershare info --long"]
    (run-sh command-str)))

(defn run-share-info-m []
  (sh->either (run-share-info)))

(defn default-share-validation-mismatch-fn
  "
  returns an instance of 'either/left' with a string value describing the condition
  "
  [share-name mount-point]
  (either/left (format "usershare %s exists, but is not mapped to mount point %s" share-name mount-point)))

(defn -validate-share-template
  "
  Template for network share validation:

  share-name: name of the share to be validated
  mount-point: path backing the share
  out-share-info: standard output from 'net usershare info' command
  present-fn: callback function invoked when the share-name exists with mount-point;
              invoked with args: share-name and mount-point;
              returns an instance of either, left indicating invalid, right indicating valid
  absent-fn: callback function invoked when the share-name does not exist;
             invoked with args: share-name and mount-point;
             returns an instance of either, left indicating invalid, right indicating valid
  mismatch-fn: callback function invoked when the shsare-name exists, but path does not match mount-point.
               defaults to implementation provided by backups.core/default-share-validation-mismatch-fn
  "
  [share-name mount-point present-fn absent-fn out-share-info & {:keys [mismatch-fn]
                                                                 :or   {mismatch-fn default-share-validation-mismatch-fn}}]
  (let [share-header (format "[%s]" share-name)
        share-section-info (->> (str/split-lines out-share-info)
                                (drop-while #(not (= % share-header))))]
    (if (empty? share-section-info)
      (absent-fn)
      (let [expected-path (format "path=%s" mount-point)
            path-line (->> share-section-info
                           (some #(and (or (= % "") (= % expected-path)) %)))]
        (if (or (nil? path-line) (= path-line ""))
          (mismatch-fn share-name mount-point)
          (present-fn))))))

(defn validate-share-for-creation-by-section-m
  "
  Validates the network share for creation:

  * [Valid] share of the given name does not exist
  * [Valid] share of the given name exists AND has path mapped to mount-point
  * [Invalid] share of the given name exists with path NOT mapped to mount-point
  "
  [share-name mount-point out-share-info]
  (let [present-fn (fn [] (either/right share-name))
        absent-fn (fn [] (either/right share-name))]
    (-validate-share-template share-name mount-point present-fn absent-fn out-share-info)))

(defn validate-share-for-creation-m! [share-name mount-point]
  (cats/>>= (run-share-info-m)
            sh-result->out-m
            (partial validate-share-for-creation-by-section-m share-name mount-point)))

(def default-share-add-options {:share/comment    "Network Backups"
                                :share/acl        "everyone:F"
                                :share/guests_ok? "n"})

(defn run-create-share
  [share-name mount-point & {:share/keys [comment acl guests_ok?]
                             :or         {comment    (:share/comment default-share-add-options)
                                          acl        (:share/acl default-share-add-options)
                                          guests_ok? (:share/guests_ok? default-share-add-options)}}]
  (let [command-str (format "net usershare add %s %s '%s' %s guest_ok=%s" share-name mount-point comment acl guests_ok?)]
    (run-sh command-str)))

(defn run-create-share-m
  ([share-name mount-point options]
   (sh->either (run-create-share share-name mount-point options)))
  ([share-name mount-point]
   (run-create-share share-name mount-point)))

(defn create-share-m!
  ([share-name mount-point options]
   (cats/do-let (validate-share-for-creation-m! share-name mount-point)
                (run-create-share-m share-name mount-point options)))
  ([share-name mount-point]
   (create-share-m! share-name mount-point default-share-add-options)))

(defn validate-share-for-deletion-by-section-m
  "
  Validates the network share for removal:

  [Valid] share of the given name exists AND has path mapped to mount-point
  [Valid] share of the given name does not exist
  [Invalid] share of the given name exists with path NOT mapped to mount-point
  "
  [share-name mount-point out-share-info]
  (let [present-fn (fn [] (either/right share-name))
        absent-fn (fn [] (either/right nil))]
    (-validate-share-template share-name mount-point present-fn absent-fn out-share-info)))

(defn validate-share-for-deletion-m! [share-name mount-point]
  (cats/>>= (run-share-info-m)
            sh-result->out-m
            (partial validate-share-for-deletion-by-section-m share-name mount-point)))

(defn run-delete-share [share-name]
  (let [command-str (format "net usershare delete %s" share-name)]
    (run-sh command-str)))

(defn run-delete-share-m [share-name]
  (sh->either (run-delete-share share-name)))

(defn run-restart-smbd []
  (let [command-str "service smbd restart"]
    (run-sh command-str)))

(defn run-restart-smbd-m! [_share-name]
  (sh->either (run-restart-smbd)))

(defn delete-share-m! [share-name mount-point]
  (cats/>>= (validate-share-for-deletion-m! share-name mount-point)
            (fn [validated-share-name]
              (if (not (nil? validated-share-name))
                (run-delete-share-m share-name)
                (either/right share-name)))
            run-restart-smbd-m!))

; endregion

; region result handling

(defn -handle-failure [failure-value]
  (console/println-red (format "%s: Failure: %s" *file* failure-value))
  (System/exit 1))

(defn -handle-success [& _success-value]
  (console/println-green (format "%s: Completed" *file*)))

(defn handle-result
  "
  Default result-handling function.
  Expects an 'either' instance. Writes the status of the operation to standard out.
  "
  [either-result]
  (let [extracted-value (cats/extract either-result)]
    (if (either/left? either-result)
      (-handle-failure extracted-value)
      (-handle-success extracted-value))))

; endregion

; region main

(defn just-mount
  "
  Top-level function used to unlock and mount.

  Expects the following arguments:
  * volume-id   - the UUID of the volume to unlock and mount
  * mount-point - the location to mount the volume
  * [optional] starting-fn - a parameter-less callback function invoked prior to the operation
  * [optional] result-fn   - a single parameter callback function that accepts an instance of 'either';
                             invoked at the conclusion of the operation."
  ([volume-id mount-point & {:keys [starting-fn result-fn]
                             :or   {starting-fn print-start-message
                                    result-fn   handle-result}}]
   (starting-fn)
   (let [result (cats/do-let (unlock-volume-m! volume-id)
                             (mount-volume-m! volume-id mount-point))]
     (result-fn result))))

(defn mount-and-share
  "
  Top-level function used to unlock, mount, and share the specified volume.

  Expects the following arguments:
  * volume-id   - the UUID of the volume to unlock and mount
  * mount-point - the location to mount the volume
  * share-name  - the name for the newly created samba share
  * [optional] starting-fn - a parameter-less callback function invoked prior to the operation
  * [optional] result-fn   - a single parameter callback function that accepts an instance of 'either';
                             invoked at the conclusion of the operation."
  ([volume-id mount-point share-name & {:keys [starting-fn result-fn]
                                        :or   {starting-fn print-start-message
                                               result-fn   handle-result}
                                        :as   options}]
   (starting-fn)
   (let [result (cats/do-let (unlock-volume-m! volume-id)
                             (mount-volume-m! volume-id mount-point)
                             (create-share-m! share-name mount-point options))]
     (result-fn result))))

(defn just-unmount
  "
  Top-level function used to unmount and lock the specified volume.

  Expects the following arguments:
  * volume-id   - the UUID of the volume to unmount and lock
  * mount-point - the location of the mounted volume
  * [optional] starting-fn - a parameter-less callback function invoked prior to the operation
  * [optional] result-fn   - a single parameter callback function that accepts an instance of 'either';
                             invoked at the conclusion of the operation.
  "
  ([volume-id mount-point & {:keys [starting-fn result-fn]
                             :or   {starting-fn print-start-message
                                    result-fn   handle-result}}]
   (starting-fn)
   (let [result (cats/do-let (unmount-volume-m! volume-id mount-point)
                             (lock-volume-m! volume-id))]
     (result-fn result))))

(defn unshare-and-unmount
  "
  Top-level function used to remove the share, unmount, and lock the specified volume.

  Expects the following arguments:
  * volume-id   - the UUID of the volume to unmount and lock
  * mount-point - the location of the mounted volume
  * share-name  - the name of the samba share to delete
  * [optional] starting-fn - a parameter-less callback function invoked prior to the operation
  * [optional] result-fn   - a single parameter callback function that accepts an instance of 'either';
                             invoked at the conclusion of the operation.
  "
  ([volume-id mount-point share-name & {:keys [starting-fn result-fn]
                                        :or   {starting-fn print-start-message
                                               result-fn   handle-result}}]
   (starting-fn)
   (let [result (cats/do-let (delete-share-m! share-name mount-point)
                             (unmount-volume-m! volume-id mount-point)
                             (lock-volume-m! volume-id))]
     (result-fn result))))

; endregion
