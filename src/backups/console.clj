(ns backups.console)

(def white-fg "\u001b[37;1m")
(def green-bg "\u001B[42m")
(def blue-bg "\u001B[0;46m")
(def red-bg "\u001b[0;43m")
(def nc "\u001B[0m")

(def header-green (format "%s%s" green-bg white-fg))
(def header-blue (format "%s%s" blue-bg white-fg))
(def header-red (format "%s%s" red-bg white-fg))

(defn println-green [message]
  (println (format "\n%s%s%s\n" header-green message nc)))

(defn println-blue [message]
  (println (format "\n%s%s%s\n" header-blue message nc)))

(defn println-red [message]
  (println (format "\n%s%s%s\n" header-red message nc)))
