(ns defacto.core
  (:require
    [clojure.walk :as walk])
  #?(:clj
     (:import
       (clojure.lang IDeref IRef))))

(defprotocol IStore
  "A store for processing `commands` and `events`. A `command` is used to invoke side
   effects, which may include emitting `events`. An `event` is specifically for recording
   an update to the local db. Designed to be used in conjunction with [[command-handler]],
   [[event-reducer]], and [[query-responder]] multi-methods."
  :extend-via-metadata true
  (-dispatch! [this command]
    "Dispatches a `command` through the system. This should call [[command-handler]] as in:

     ```clojure
     (command-handler {:defacto.core/store this ....ctx-kvs} command cb-to-emit-events)
     ```

     `cb-to-emit-events` should invoke [[event-reducer]] and update the internal db.")
  (-subscribe [this query]
    "Returns a deref-able and watchable subscription to a `query` implemented by [[query-responder]].
     The subscription should be updated any time the query results change."))

(defmulti ^{:arglists '([{::keys [store] :as ctx-map} command emit-cb])} command-handler
          "Handles a `command` in whatever fashion you decide. `emit-cb` can be called
           with 0 or more `events`. A command should be a vector with a keyword in first
           position denoting its `action`.

           ```clojure
           (defmethod command-handler :do-something!
             [ctx-map command emit-cb]
             (emit-cb [:something-happened {:some :data}]))
           ```"
          (fn [_ [action] _] action))

(defmulti ^{:arglists '([db event])} event-reducer
          "Reduces an `event` over the current `db` and returns a new db value.
           Your [[event-reducer]] implementation should have NO SIDE EFFECTS. Those belong in
           [[command-handler]]s.

           ```clojure
           (defmethod event-reducer :something-happened
             [db event]
             (update db ...))
           ```"
          (fn [_ [type]] type))

(defmulti ^{:arglists '([db query-responder])} query-responder
          "Processes a query and returns the data from the db. Your [[query-responder]]
           implementation should have NO SIDE EFFECTS. Those belong in [[command-handler]]s.

           ```clojure
           (defmethod query-responder :data?
             [db query]
             (get-in db [...]))
           ```"
          (fn [_ [resource]] resource))

(defprotocol IInitialize
  "Extend this protocol to have components in your `ctx-map` get initialized with the store upon creation."
  (init! [_ store]))

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

(deftype DefactoStore [watchable-store ->atom-sub]
  IDeref
  (#?(:cljs -deref :default deref) [_] @watchable-store)

  IStore
  (-dispatch! [this command]
    (-dispatch! watchable-store command)
    this)
  (-subscribe [_ query]
    (let [sub (->atom-sub query (query-responder @watchable-store query))]
      (add-watch watchable-store query (fn [_ _ old new]
                                         (let [prev (query-responder old query)
                                               next (query-responder new query)]
                                           (when-not (= prev next)
                                             (reset! sub next)))))
      (->ImmutableSubscription sub))))

(deftype StandardSubscription [atom-db query watchable?]
  IDeref
  (#?(:cljs -deref :default deref) [_] (query-responder @atom-db query))

  #?@(:cljs
      [IWatchable
       (-add-watch [this key f] (cond-> this watchable? (add-watch* atom-db key f)))
       (-remove-watch [_ key] (remove-watch atom-db key))
       (-notify-watches [_ old new] (-notify-watches atom-db old new))]

      :default
      [IRef
       (addWatch [this key f] (cond-> this watchable? (add-watch* atom-db key f)))
       (removeWatch [_ key] (remove-watch atom-db key))]))

(deftype WatchableStore [ctx-map atom-db watch-subs?]
  IDeref
  (#?(:cljs -deref :default deref) [_] @atom-db)

  IStore
  (-dispatch! [this command]
    (command-handler (assoc ctx-map ::store this)
                     command
                     (fn [event]
                       (swap! atom-db event-reducer event)
                       nil)))
  (-subscribe [_ query]
    (->StandardSubscription atom-db query watch-subs?))

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
                        for use by this library. Any node in the `ctx-map` that satisfies
                        [[IInitialize]] will be initialized with the store upon creation.

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
   (let [base-store (->WatchableStore ctx-map (atom init-db) false)
         store (->DefactoStore base-store (->query-cached-sub-fn ->sub))]
     (walk/postwalk (fn [x]
                      (when (satisfies? IInitialize x)
                        (init! x store))
                      x)
                    ctx-map)
     store)))

(defn dispatch!
  "Dispatches a `command` through the store. The `command` should be a vector with a keyword in
   first position denoting its type.

   ```clojure
   (dispatch! store [:update-thing! {:id 123 :attribute \"value\"}])
   ```"
  [store command]
  (-dispatch! store command)
  store)

(defn emit!
  "Emit an `event` through the store which may update the db. `event` should be a vector
   with a keyword in first position denoting its type.

   ```clojure
   (emit! store [:thing-updated {:id 123 ...}])
   ```"
  [store event]
  (dispatch! store [::emit! event]))

(defn subscribe
  "Returns a deref-able window into the database. `query` should be a vector with a keyword
   in first position denoting its type.

   ```clojure
   (subscribe store [:thing {:id 123}])
   ```"
  [store query]
  (-subscribe store query))

(defmethod command-handler ::emit!
  [_ [_ event :as _command] emit-cb]
  (emit-cb event))

(defmethod event-reducer :default
  [db _event]
  db)
