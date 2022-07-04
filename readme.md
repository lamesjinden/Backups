# overview

__Backups__ represents a set of scripts (intended to for execution via [babashka](https://github.com/babashka/babashka)) to automate decryption, mounting, and sharing of a given storage device.
The specified device should be LUKS-encrypted. Execution of the associated _mount-and-share_ script prompts for the 
decryption key. Once decrypted, a SAMBA network share is created for the specified device.

Upon successful completion of the _mount-and-share_ script, Linux and Windows client machines can target the created SAMBA network share to capture system backups.
* Windows clients can use Windows Backup and Restore
  * see [Backup and Restore in Windows](https://support.microsoft.com/en-us/windows/backup-and-restore-in-windows-352091d2-bb9d-3ea3-ed18-52ef2b88cbef#WindowsVersion=Windows_10)
* Linux clients can use _gnome backups/Deja Dup_
  * see [How To Set Up Automatic Backup With Deja Dup](https://www.addictivetips.com/ubuntu-linux-tips/set-up-automatic-backup-deja-dup/)

Scripts are idempotent; following successful execution, they can be re-executed without effect. Furthermore, should an error arise mid-execution (e.g. a SAMBA share of the given name already exists),
re-execution will skip over previously completed steps.

When the SAMBA share is no longer needed, execution of the _unshare-and-unmount_ script will undo the effects of the corresponding _mount-and-share_ script. 
That is, the SAMBA share will be deleted, device unmounted, and the device will be locked.

## motivation

* Writing a few hundred lines of clojure is more fun than 10 lines of bash.
* Pretend working with shell results as monads is a good idea
* in a word: chaos

## usage

_After updating place-holder values in mount_template.clj and unmount_template.clj..._

### mount and share

Execute a _mount-and-share_ script to create a network share for the specified device. 

from the repository root:

```bash
chmod +x ./src/backups/mount_template.clj # make the script executable (one-time only)
./src/backups/mount_template.clj
```

_or_ via __babashka__, explicitly:

```bash
bb ./src/backups/mount_template.clj
```

### unshare and unmount

Similarly, execute an _unshare-and-unmount_ script to remove a network share and unmount the specified device.

from the repository root:

```bash
chmod +x ./src/backups/mount_template.clj # make the script executable (one-time only)
./src/backups/unmount_template.clj
```

_or_ via __babashka__, explicitly:

```bash
bb ./src/backups/unmount_template.clj
```

## assumptions

* [babashka](https://github.com/babashka/babashka) is installed and available on the user's path
* Target devices are expected to be LUKS encrypted
  * LUKS encryption implies the executing system is Linux-based
* The default label given to decrypted LUKS devices follows the following pattern:
  * luks-<DEVICE_UUID>
* Decrypted LUKS devices are made available at `/dev/mapper/`
* Output from `net usershare info --long` includes a line describing the `path` that backs the share (i.e. the shared directory)

## commands

The following commands are executed by __Backups__:

* `ls -l /dev/disk/by-uuid`
* `udisksctl info --block-device /dev/disk/by-uuid/$DEVICE_UUID`
* `udisksctl unlock --block-device /dev/disk/by-uuid/$DEVICE_UUID"`
* `mount`
* `mount /dev/mapper/luks-$DEVICE_UUD $MOUNT_POINT`
* `net usershare info --long`
* `net usershare add $SHARE_NAME $MOUNT_POINT 'Network Backups' everyone:F guest_ok=n`
* `net usershare delete $SHARE_NAME`
* `umount $MOUNT_POINT`
* `udisksctl lock --block-device /dev/disk/by-uuid/$DEVICE_UUID"`

## todo

* echo the actual commands when executed. or, as a \*debug\* flag
* treat error output better from _handle-error_
  * consider only passing the error message through __either__ instances
    * prepend a context message that identifies what command/step failed 
    * replace \n with newline chars
