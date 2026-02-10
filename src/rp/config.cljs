(ns rp.config
  "App configuration and version info.")

;; Version injected at build time via :closure-defines
;; Shows "dev" during development, actual version in release builds
(goog-define VERSION "dev")
