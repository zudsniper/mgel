(require '[babashka.fs :as fs])
(fs/copy-tree "addons/" "test_addons/" {:replace-existing true})
