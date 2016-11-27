{:cljsbuild-out "resources/public/js/compiled/out"
 :output-dir    "resources/public/js/cljs_bundles"
 :bundles       [{:name          maria-user
                  :require       [[cljs.spec :include-macros true]
                                  [maria.user :include-macros true]]
                  :provided      [maria.core]
                  :require-cache [maria.eval]}
                 #_{:name          quil
                    :require       [[quil.core :include-macros true]]
                    :provided      [maria.core maria.user]
                    :exclude-cache [cljs.core cljs.core$macros]
                    :dependencies  [[quil "2.5.0"]]}]}