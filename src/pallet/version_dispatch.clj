(ns pallet.version-dispatch
  "Dispatch that is version aware.

A version is a dotted string, e.g. \"1.0.3\", which is represented as a vector
`[1 0 3]`.

A version specification is either a version vector, which matches a single
version (and all point versions thereof), or a vector of two elements,
specifying an inclusive version range. A nil in the version vector signifies an
open end to the range.

The basic idea is that you wish to dispatch on hierarchy where the dispatched
data may provide a version."
  (:require
   [clojure.string :as string]
   [pallet.kb :refer [os-hierarchy]]
   [pallet.core.version-dispatch
    :refer [os-match-less version-spec-more-specific version-map]]
   [pallet.exception :refer [compiler-exception]]
   [pallet.node :refer [os-family os-version]]
   [pallet.plan :refer [plan-context defmulti-every defmethod-plan]]
   [pallet.session :refer [target]]
   [pallet.utils.multi :as multi]
   [pallet.versions :refer [as-version-vector version-matches? version-spec?]]))

(defn ^:internal hierarchy-vals
  "Returns all values in a hierarchy, whether parents or children."
  [hierarchy]
  (set
   (concat
    (keys (:parents hierarchy))
    (keys (:descendants hierarchy)))))

(defn ^{:internal true} dispatch-version
  [sym os os-version version args hierarchy methods]
  (letfn [(matches? [[i _]]
            (and (isa? hierarchy os (:os i))
                 (version-matches? os-version (:os-version i))
                 (version-matches? version (:version i))))]
    (if-let [[_ f] (first (sort
                           (comparator
                            (fn [x y]
                              ((os-match-less hierarchy)
                               (key x) (key y))))
                           (filter matches? methods)))]
      (apply f os os-version version args)
      (if-let [f (:default methods)]
        (apply f os os-version version args)
        (throw
         (ex-info
          (format "No %s method for :os %s :os-version %s :version %s"
                  sym os os-version version)
          {:reason :defmulti-version-method-missing
           :multi-version sym
           :os os
           :os-version os-version
           :version version}))))))


(defn ^:internal multi-version-selector
  [methods hierarchy]
  (->>
   methods
   (sort
    (comparator
     (fn [x y]
       ((os-match-less hierarchy)
        (key x) (key y)))))
   first))

(defn os-matches? [k os]
  (and (map? k) (isa? os-hierarchy os (:os k))))

(defn os-version-matches [k os-version]
  (and (map? k) (version-matches? os-version (:os-version k))))

(defn component-version-matches [k version]
  (and (map? k) (version-matches? version (:version k))))

(defmacro defmulti-version
  "Defines a multi-version function used to abstract over an operating system
hierarchy, where dispatch includes an optional `os-version`. The `version`
refers to a software package version of some sort, on the specified `os` and
`os-version`."
  {:arglists
   '[[name [os os-version version & args :as dispatch] & {:keys [hierarchy]}]]}
  [name & args]
  (let [{:keys [name dispatch options]} (multi/name-with-attributes name args)
        hierarchy (:hierarchy options `os-hierarchy)
        attr (dissoc (meta name) [:file :line :ns])]
    (when (< (count dispatch) 3)
      (throw
       (ex-info
        "Invalid dispatch vector. Must start with os, os-version, and version arguments."
        {:dispatch dispatch})))
    `(defmulti-every ~name
       ~@(if attr [attr])
       [(fn [k# [os# os-version# version#]]
          (and (map? k#) (isa? ~hierarchy os# (:os k#))))
        (fn [k# [os# os-version# version#]]
          (and (map? k#) (version-matches? os-version# (:os-version k#))))
        (fn [k# [os# os-version# version#]]
          (and (map? k#) (version-matches? version# (:version k#))))]
       {:selector #(multi-version-selector % ~hierarchy)})))

(defmacro defmethod-version
  "Adds a method to the specified defmulti-version function for the specified
  `dispatch-value`."
  [multi-version {:keys [os os-version version] :as dispatch-value}
   [& args] & body]
  `(multi/defmethod ~multi-version ~dispatch-value [~@args] ~@body))

(defmacro defmulti-version-plan
  "Defines a multi-version function used to abstract over an operating system
hierarchy, where dispatch includes an optional `os-version`. The `version`
refers to a software package version of some sort, on the specified `os` and
`os-version`."
  {:arglists
   '[[name [session version & args :as dispatch] & {:keys [hierarchy]}]]}
  [name & args]
  (let [{:keys [name dispatch options]} (multi/name-with-attributes name args)
        hierarchy (:hierarchy options `os-hierarchy)
        attr (dissoc (meta name) [:file :line :ns])]
        (when (< (count dispatch) 2)
      (throw
       (ex-info
        "Invalid dispatch vector. Must start with session and version arguments."
        {:dispatch dispatch})))
    `(defmulti-every ~name
       ~@(if attr [attr])
       [(fn [k# [session# version#]]
          (and (map? k#)
               (isa? ~hierarchy (os-family (target session#)) (:os k#))))
        (fn [k# [session# version#]]
          (and (map? k#)
               (version-matches?
                (as-version-vector (os-version (target session#)))
                (:os-version k#))))
        (fn [k# [session# version#]]
          (and (map? k#) (version-matches? version# (:version k#))))]
       {:selector #(multi-version-selector % ~hierarchy)})))

(defmacro defmethod-version-plan
  "Adds a method to the specified defmulti-version function for the specified
  `dispatch-value`."
  [multi-version {:keys [os os-version version] :as dispatch-value}
   [& args] & body]
  `(defmethod-plan ~multi-version ~dispatch-value [~@args] ~@body))

(defn os-map
  "Construct an os version map. The keys should be maps with :os-family
and :os-version keys. The :os-family value should be take from the
`os-hierarchy`. The :os-version should be a version vector, or a version range
vector."
  [{:as os-value-pairs}]
  (version-map os-hierarchy :os :os-version os-value-pairs))

(defn os-map-lookup
  [os-map]
  (get os-map {:os (os-family) :os-version (as-version-vector (os-version))}))
