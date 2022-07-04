#!/usr/bin/env bb

(ns backups.mount-template
  (:require [backups.core :as b]))

(def volume-id "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
(def mount-point "/mnt/my-backup-drive")
(def share-name b/default-share-name)

(b/mount-and-share volume-id mount-point share-name)
