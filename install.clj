(require '[babashka.fs :as fs])
(require '[babashka.tasks :refer [shell]])

(fs/copy-tree "addons/" "../tf/addons/" {:replace-existing true})
(fs/copy-tree "maps/" "../tf/maps/" {:replace-existing true})

(shell {:dir "../tf/addons/sourcemod/scripting/"} "./compile.sh")

