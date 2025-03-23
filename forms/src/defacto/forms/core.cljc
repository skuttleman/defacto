(ns defacto.forms.core
  (:refer-clojure :exclude [flatten])
  (:require
    [defacto.core :as defacto]))

(defn ^:private flatten* [m path]
  (mapcat (fn [[k v]]
            (let [path' (conj path k)]
              (cond
                (map? v) (flatten* v path')
                (vector? v) (flatten* (into {} (map-indexed vector) v) path')
                :else [[path' v]])))
          m))

(defn ^:private assoc-in* [m [k :as path] v]
  (cond
    (empty? path)
    v

    (and (int? k) (or (nil? m) (vector? m)))
    (loop [vector (or m [])]
      (if (> k (count vector))
        (recur (conj vector nil))
        (update vector k assoc-in* (next path) v)))

    :else
    (assoc m k (assoc-in* (get m k) (next path) v))))

(defn ^:private flatten [m]
  (into {} (flatten* m [])))

(defn ^:private nest [m]
  (reduce-kv assoc-in* {} m))

(defn id
  "The id of the form"
  [form]
  (::id form))

(defn opts
  "The opts of the form"
  [form]
  (::opts form))

(defn data
  "Extract the canonical model of data of the form."
  [form]
  (when form
    (nest (::current form))))

(defn initial
  "Extract the canonical model of initial value of the form."
  [form]
  (when form
    (nest (::init form))))

(defn modify
  "Modifies the value of a form at a path by applying a function
   and any additional args.

  (-> form
      (modify [:some] (fnil conj []) {:path 1})
      (modify [:some 0 :path] + [7 12])
      data)
  ;; => {:some [{:path 20}]}"
  ([form path f]
   (modify form path f nil))
  ([form path f args]
   (when form
     (let [{:keys [remove-nil? update-fn] :or {update-fn identity}} (opts form)
           model (apply update-in (data form) path f args)
           remove? (and (nil? (get-in model path)) remove-nil?)
           long-path? (next path)
           model (cond-> model
                   (and remove? long-path?)
                   (update-in (butlast path) dissoc (last path))

                   (and remove? (not long-path?))
                   (dissoc (first path)))]
       (assoc form ::current (flatten (update-fn model)))))))

(defn change
  "Changes a value of a form at a path.

  (-> form
      (change [:some 0 :path] 42)
      data)
  ;; => {:some [{:path 42}]}"
  [form path value]
  (modify form path (constantly value)))

(defn changed?
  "Does the current value of the form differ from the initial value?"
  ([{::keys [current init]}]
   (not= current init))
  ([{::keys [current init]} path]
   (not= (get current path) (get init path))))

(defn create
  "Creates a form from `init-data` which must be a `map`. Supported opts

  :remove-nil?   - when true, calls to [[change]] will remove the path instead of setting it.
                   defaults to `false`."
  [id init-data opts]
  {:pre [(or (nil? init-data) (map? init-data))]}
  (let [internal-data (flatten init-data)]
    {::id      id
     ::init    internal-data
     ::current internal-data
     ::opts    opts}))


;; commands
(defmethod defacto/command-handler ::ensure!
  [{::defacto/keys [store]} [_ form-id params opts] emit-cb]
  (when-not (defacto/query-responder @store [::?:form form-id])
    (emit-cb [::created form-id params opts])))


;; queries
(defmethod defacto/query-responder ::?:forms
  [db _]
  (vals (::-forms db)))

(defmethod defacto/query-responder ::?:form
  [db [_ form-id]]
  (get-in db [::-forms form-id]))


;; events
(defmethod defacto/event-reducer ::created
  [db [_ form-id data opts]]
  (assoc-in db [::-forms form-id] (create form-id data opts)))

(defmethod defacto/event-reducer ::changed
  [db [_ form-id path value]]
  (update-in db [::-forms form-id] change path value))

(defmethod defacto/event-reducer ::modified
  [db [_ form-id path f & args]]
  (update-in db [::-forms form-id] modify path f args))

(defmethod defacto/event-reducer ::destroyed
  [db [_ form-id]]
  (update db ::-forms dissoc form-id))
