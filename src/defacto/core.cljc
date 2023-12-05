(ns defacto.core
  (:require
    [clojure.core.async :as async])
  #?(:clj
     (:import
       (clojure.lang IDeref IRef))))

(defprotocol IStore
  "A store for processing `commands` and `events`. A `command` is used to invoke side
   effects, which may include emitting `events`. An `event` is specifically for recording
   an update to the local db which can be subscribed to. Designed to be used in conjunction
   with [[command-handler]], [[event-handler]], and [[query]] multi-methods."
  :extend-via-metadata true
  (-dispatch! [this command]
    "Dispatches a `command` through the system. This should call [[command-handler]] as in:

     ```clojure
     (command-handler {:defacto.core/store this ....ctx-kvs} command cb-to-emit-events)
     ```

     `cb-to-emit-events` should invoke [[event-handler]] and update the internal db.")
  (-subscribe [this query]
    "Returns a deref-able and watchable subscription to a `query` implemented by [[query]].
     The subscription should be updated any time the query results change."))

(defmulti ^{:arglists '([{::keys [store] :as ctx-map} command emit-cb])} command-handler
          "Handles a `command` in whatever fashion you decide. `emit-cb` can be called
           with 0 or more `events`. An event should be a vector with a keyword in first
           position denoting its type.

           ```clojure
           (defmethod command-handler :do-something!
             [ctx-map command emit-cb]
             (emit-cb [:something-happened {:some :data}]))
           ```"
          (fn [_ [type] _] type))

(defmulti ^{:arglists '([db event])} event-handler
          "Reduces an `event` over the current `db` and returns a new db value.
           Your [[event-handler]] implementation should have NO SIDE EFFECTS. Those belong in
           [[command-handler]]s.

           ```clojure
           (defmethod event-handler :something-happened
             [db event]
             (update db ...))
           ```"
          (fn [_ [type]] type))

(defmulti ^{:arglists '([db query-handler])} query-handler
          "Processes a query and returns the data from the db. Your [[query]]
           implementation should have NO SIDE EFFECTS.

           ```clojure
           (defmethod query :data?
             [db query]
             (get-in db [...]))
           ```"
          (fn [_ [type]] type))

(defn ^:private add-watch* [store sub key f]
  (add-watch sub key (fn [key _ old new]
                       (f key store old new)))
  store)

(defn ^:private ->query-cached-sub-fn [->sub]
  (let [query->sub (atom {})]
    (fn [query result]
      (let [sub (-> query->sub
                    (swap! update query #(or % (->sub result)))
                    (get query))]
        (doto sub (reset! result))))))

(deftype ImmutableSubscription [sub]
  IDeref
  (#?(:cljs -deref :default deref) [_] @sub)

  #?@(:cljs
      [IWatchable
       (-add-watch [this key f] (add-watch* this sub key f))
       (-remove-watch [_ key] (remove-watch sub key))
       (-notify-watches [_ old new] (-notify-watches sub old new))]

      :default
      [IRef
       (addWatch [this key f] (add-watch* this sub key f))
       (removeWatch [_ key] (remove-watch sub key))]))

(deftype DefactoStore [store ->atom-sub]
  IDeref
  (#?(:cljs -deref :default deref) [_] @store)

  IStore
  (-dispatch! [this command]
    (-dispatch! store command)
    this)
  (-subscribe [_ query]
    (let [sub (->atom-sub query (query-handler @store query))]
      (add-watch store query (fn [_ _ old new]
                               (let [prev (query-handler old query)
                                     next (query-handler new query)]
                                 (when-not (= prev next)
                                   (reset! sub next)))))
      (->ImmutableSubscription sub))))

(deftype UnwatchableSubscription [atom-db query]
  IDeref
  (#?(:cljs -deref :default deref) [_] (query-handler @atom-db query))

  #?@(:cljs
      [IWatchable
       (-add-watch [this _ _] this)
       (-remove-watch [this _] this)
       (-notify-watches [this _ _] this)]

      :default
      [IRef
       (addWatch [this _ _] this)
       (removeWatch [this _] this)]))

(deftype WatchableStore [ctx-map atom-db]
  IDeref
  (#?(:cljs -deref :default deref) [_] @atom-db)

  IStore
  (-dispatch! [this command]
    (command-handler (assoc ctx-map ::store this)
                     command
                     (fn [event]
                       (swap! atom-db event-handler event)
                       nil)))
  (-subscribe [_ query]
    (->UnwatchableSubscription atom-db query))

  #?@(:cljs
      [IWatchable
       (-add-watch [this key f] (add-watch* this atom-db key f))
       (-remove-watch [_ key] (remove-watch atom-db key))
       (-notify-watches [this _ _] this)]

      :default
      [IRef
       (addWatch [this key f] (add-watch* this atom-db key f))
       (removeWatch [_ key] (remove-watch atom-db key))]))

(defn create
  "Creates a basic, deref-able state store which takes these arguments.

   `ctx-map`          - any arbitrary map of clojure data. keys with the namespace
                        `defacto*` (i.e. `:defacto.whatever/some-key`) are reserved
                        for use by this library.

   `init-db`          - the initial value of your db.

   `->sub` (optional) - a function that returns something that behaves like an atom.
                        For example, [[clojure.core/atom]] or [[reagent.core/atom]].
                        Specifically, it needs to support these protocol methods:

                        clj  - `IAtom/reset`, `IDeref/deref`
                        cljs - `IReset/-reset`, `IDeref/-deref`

                        If you want to *watch* your subscriptions, then the return value
                        of `->sub` must also satisfy:

                        clj  - `IRef/addWatch`, `IRef/removeWatch` (and notify watchers
                               in impl of `IAtom/reset`)
                        cljs - `IWatchable/-add-watch` `IWatchable/-remove-watch, and
                               `IWatchable/-notify-watches`"
  ([ctx-map init-db]
   (create ctx-map init-db atom))
  ([ctx-map init-db ->sub]
   (let [base-store (->WatchableStore ctx-map (atom init-db))]
     (->DefactoStore base-store (->query-cached-sub-fn ->sub)))))

(defn dispatch!
  "Dispatches a `command` through the store. The `command` should be a vector with a keyword in
   first position denoting its type.

   ```clojure
   (dispatch! store [:update-thing! {:id 123 :attribute \"value\"}])
   ```"
  [store command]
  (-dispatch! store command)
  store)

(defn subscribe
  "Returns a deref-able window into the database. `query` should be a vector with a keyword in first
   position denoting its type.

   ```clojure
   (subscribe store [:thing? {:id 123}])
   ```"
  [store query]
  (-subscribe store query))

(defmethod command-handler ::sync!
  [_ [_ event] emit]
  (emit event))

(defmethod command-handler ::async!
  [_ [_ event] emit]
  (async/go
    (emit event)))

(defmethod event-handler :default
  [db _event]
  db)
