(ns maria.code.commands
  (:require [applied-science.js-interop :as j]
            ["prosemirror-model" :refer [Fragment Slice]]
            ["prosemirror-state" :refer [TextSelection Selection NodeSelection]]
            ["prosemirror-commands" :as pm.cmd]
            [clojure.string :as str]
            [nextjournal.clojure-mode.node :as n]
            [maria.prose.schema :refer [schema]]
            [maria.eval.sci :as sci]
            [promesa.core :as p]
            [nextjournal.clojure-mode.extensions.eval-region :as eval-region]
            [maria.eval.repl :refer [*context*]]
            [sci.async :as a]
            [maria.util :as u]))

(j/js

  (defn set-result! [{:keys [!result]} v]
    (reset! !result v))

  (defn bind-prose-command [this cmd]
    (fn []
      (let [{{:keys [state dispatch]} :proseView} this]
        (cmd state dispatch))))

  (defn prose:cursor-node [state]
    (let [sel (.-selection state)]
      (or (.-node sel) (.-parent (.-$from sel)))))

  (defn prose:convert-to-code [state dispatch]
    (let [{:as node :keys [type]} (prose:cursor-node state)
          {{:keys [heading paragraph code_block]} :nodes} schema]
      (when (and (#{heading paragraph} type)
                 (= 0 (.-size (.-content node))))
        ((pm.cmd/setBlockType code_block) state dispatch)
        true)))
  (defn code:cursors [{{:keys [ranges]} :selection}]
    (reduce (fn [out {:keys [from to]}]
              (if (not= from to)
                (reduced nil)
                (j/push! out from))) [] ranges))
  (defn code:cursor [state]
    (-> (code:cursors state)
        (u/guard #(= (count %) 1))
        first))
  (defn code:paragraph-after-code [{:keys [proseView]} {:keys [state]}]
    (boolean
     (when (and (= (code:cursor state)
                   (.. state -doc -length))
                (pm.cmd/exitCode (.-state proseView) (.-dispatch proseView)))
       (.focus proseView)
       true)))

  (defn code:arrow-handler
    "Moves cursor out of code block when navigating out of an edge"
    [{:keys [getPos
             codeView
             proseNode
             proseView]} unit dir]
    (let [{{:keys [doc selection]} :state} codeView
          {:keys [main]} selection
          {:keys [from to]} (if (= unit "line")
                              (.lineAt doc (.-head main))
                              main)
          at-end-of-block? (>= to (.-length doc))
          at-end-of-doc? (= (+ (getPos) (.-nodeSize proseNode))
                            (.. proseView -state -doc -content -size))]
      (cond (not (.-empty main)) false ;; something is selected
            (and (neg? dir) (pos? from)) false ;; moving backwards but not at beginning
            (and (pos? dir) (not at-end-of-block?)) false ;; moving forwards, not at end
            (and (= "line" unit)
                 at-end-of-block?
                 at-end-of-doc?
                 (pos? dir)) (when (pm.cmd/exitCode (.-state proseView) (.-dispatch proseView))
                               (.focus proseView)
                               true)
            :else
            (let [next-pos (if (neg? dir)
                             (getPos)
                             (+ (getPos) (.-nodeSize proseNode)))
                  {:keys [doc tr]} (.-state proseView)
                  selection (.near Selection (.resolve doc next-pos) dir)]
              (doto proseView
                (.dispatch (.. tr
                               (setSelection selection)
                               (scrollIntoView)))
                (.focus))))))

  (defn prose:arrow-handler [dir]
    (fn [state dispatch view]
      (boolean
       (when (and (.. state -selection -empty) (.endOfTextblock view dir))
         (let [$head (.. state -selection -$head)
               {:keys [doc]} state
               pos (if (pos? dir)
                     (.after $head)
                     (.before $head))
               next-pos (.near Selection (.resolve doc pos) dir)]
           (when (= :code_block (j/get-in next-pos [:$head :parent :type :name]))
             (dispatch (.. state -tr (setSelection next-pos)))
             true))))))

  (defn code:empty? [{{:keys [length text]} :doc}]
    (or (zero? length)
        (and (= 1 (count text))
             (str/blank? (first text)))))

  (defn code:length [state] (.. state -doc -length))

  (defn code:split [{:keys [getPos proseView]} {:keys [state]}]
    (if-let [cursor-prose-pos (some-> (code:cursor state)
                                      (u/guard #(n/program? (n/tree state %)))
                                      u/type:number ;; cljs bug doesn't like use of `guard` above
                                      (+ 1 (getPos)))]
      (do
        (.dispatch proseView (.. proseView -state -tr
                                 (split cursor-prose-pos)
                                 (scrollIntoView)))
        true)
      false))

  (defn code:remove [{:keys [getPos proseView codeView proseNode]}]
    (let [pos (getPos)
          tr (.. proseView -state -tr)]
      (doto tr
        (.deleteRange pos (+ pos (.-nodeSize proseNode)))
        (as-> tr
              (.setSelection tr (.near TextSelection (.resolve (.-doc tr) pos) -1)))
        (.scrollIntoView))

      (doto proseView
        (.dispatch tr)
        (.focus)))
    true)

  (defn code:remove-on-backspace [this {:keys [state]}]
    (when (zero? (code:cursor state))
      (when (code:empty? state)
        (code:remove this))
      true))

  (defn code:convert-to-paragraph [{:as this :keys [proseView getPos]} {:keys [state]}]
    (when (code:empty? state)
      (let [{{:keys [paragraph]} :nodes} schema
            {{:keys [tr selection]} :state} proseView
            node (.. selection -$from -parent)
            pos (getPos)
            tr (.setBlockType tr pos (+ pos (.-nodeSize node)) paragraph)]
        (doto proseView
          (.dispatch tr)
          (.focus))
        true)))

  (defn code:block-str [{:keys [codeView]}]
    (.. codeView -state -doc (toString)))

  )

(j/js
  (defn index [{:keys [proseView getPos]}]
    (.. proseView -state -doc (resolve (getPos)) (index 0))))

(j/defn code:ns
  "Returns the first evaluated namespace above this code block"
  [^js {:as this :keys [proseView]}]
  (u/akeep-first #(some-> (j/get-in % [:spec :!result])
                          deref
                          :value
                          (u/guard (partial instance? sci.lang/Namespace)))
                 (dec (index this))
                 -1
                 (.. proseView -docView -children)))

(j/defn code:eval-string!
  ([this source] (code:eval-string! nil this source))
  ([opts this source]
   (let [opts (update opts :ns #(or % (code:ns this)))
         result (try (sci/eval-string @*context* opts source)
                     (catch js/Error e ^:clj {:error e}))]
     (if (a/await? result)
       (a/await
        (-> result
            (.then (fn [result] (set-result! this result)))
            (.catch (fn [e] (set-result! this {:error e})))))
       (set-result! this result)))))

(defn code:eval-block! [this]
  (code:eval-string! this (code:block-str this))
  true)


(defn prose:eval-doc! [^js prose-view]
  (let [node-views (->> prose-view
                        .-docView
                        .-children
                        (keep (j/get :spec))
                        (filterv (j/get :codeView)))]
    ((fn continue [i]
       (when-let [node-view (nth node-views i nil)]
         (let [value (code:eval-string! {:ns (:last-ns @*context*)} node-view (code:block-str node-view))]
           (if (a/await? value)
             (p/do value (continue (inc i)))
             (continue (inc i)))))
       nil) 0)))

(j/defn prose:next-code-cell [proseView]
  (when-let [index (.. proseView -state -selection -$anchor (index 0))]
    (when-let [next-node (->> proseView
                              .-docView
                              .-children
                              (drop (inc index))
                              (keep (j/get :spec))
                              first)]
      (j/js (let [{:keys [codeView dom]} next-node]
              (.dispatch codeView
                         {:selection {:anchor 0
                                      :head 0}})
              (.focus codeView)
              (.scrollIntoView dom {:block :center})))
      true)))

(j/js

  (defn code:eval-current-region [{:as this {:keys [state]} :codeView}]
    (when-let [source (u/guard (eval-region/current-str state) (complement str/blank?))]
      (code:eval-string! this source))
    true)

  (defn code:copy-current-region [{:keys [state]}]
    (j/call-in js/navigator [:clipboard :writeText] (eval-region/current-str state))
    true))