(require '[babashka.fs :as fs])
(require '[babashka.tasks :refer [shell]])

(fs/copy-tree "addons/" "../tf/addons/" {:replace-existing true})
(fs/copy-tree "maps/" "../tf/maps/" {:replace-existing true})

(fs/copy-tree "maps/" "../tf/maps/" {:replace-existing true})

(shell {:dir "../tf/addons/sourcemod/scripting/"} "./compile.sh")
(fs/copy "../tf/addons/sourcemod/scripting/compiled/mge.smx"
         "../tf/addons/sourcemod/plugins/mge.smx"
         {:replace-existing true})

(shell "screen -S tf2 -p 0 -X stuff  \"sm plugins unload_all\n\"")
(shell "screen -S tf2 -p 0 -X stuff  \"sm plugins refresh\n\"")

