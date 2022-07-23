(require '[babashka.fs :as fs])
(fs/copy-tree "addons/" "../tf/addons/" {:replace-existing true})
(fs/copy-tree "maps/" "../tf/maps/" {:replace-existing true})
