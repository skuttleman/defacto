(ns defacto.core
  (:require
    [clojure.walk :as walk]
    [defacto.impl :as impl]))

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
   (create ctx-map init-db nil))
  ([ctx-map init-db {:keys [->sub] :or {->sub atom} :as opts}]
   (let [api {:command-handler (:command-handler opts command-handler)
              :event-reducer   (:event-reducer opts event-reducer)
              :query-responder (:query-responder opts query-responder)}
         store (impl/create ctx-map init-db api ->sub)]
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
  (impl/-dispatch! store command)
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
  (impl/-subscribe store query))

(defmethod command-handler ::emit!
  [_ [_ event :as _command] emit-cb]
  (emit-cb event))

(defmethod event-reducer :default
  [db _event]
  db)
