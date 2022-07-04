#!/usr/bin/env bb

(ns backups.unmount-template
  (:require [backups.core :as b]))

(def volume-id "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
(def mount-point "/mnt/my-backup-drive")
(def share-name b/default-share-name)

(b/unshare-and-unmount volume-id mount-point share-name)