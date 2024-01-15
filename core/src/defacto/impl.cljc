(ns defacto.impl
  #?(:clj
     (:import
       (clojure.lang IDeref IRef))))

(defprotocol IStore
  "A store for processing `commands` and `events`. A `command` is used to invoke side
   effects, which may include emitting `events`."
  (-dispatch! [this command]
    "Dispatches a `command` through the system causing potential side-effects and/or emmitting `events`
     which update the internal db.")
  (-subscribe [this query]
    "Returns a deref-able and watchable subscription to a `query`.
     The subscription should be updated any time the query results change."))

(defn ^:private add-watch* [store sub key f]
  (add-watch sub key (fn [key _ old new]
                       (f key store old new)))
  store)

;; TODO - do we always want to do this? Maybe LRU?
(defn ^:private ->query-cached-sub-fn [->sub]
  (let [query->sub (atom {})]
    (fn [query result]
      (let [sub (-> query->sub
                    (swap! update query #(or % (->sub result)))
                    (get query))]
        (doto sub (reset! result))))))

(deftype ^:private ImmutableSubscription [sub]
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

(deftype DefactoStore [watchable-store ->atom-sub api]
  IDeref
  (#?(:cljs -deref :default deref) [_] @watchable-store)

  IStore
  (-dispatch! [this command]
    (-dispatch! watchable-store command)
    this)
  (-subscribe [_ query]
    (let [responder (:query-responder api)
          sub (->atom-sub query (responder @watchable-store query))]
      (add-watch watchable-store query (fn [_ _ old new]
                                         (let [prev (responder old query)
                                               next (responder new query)]
                                           (when-not (= prev next)
                                             (reset! sub next)))))
      (->ImmutableSubscription sub))))

(deftype ^:private StandardSubscription [atom-db query responder watchable?]
  IDeref
  (#?(:cljs -deref :default deref) [_] (responder @atom-db query))

  #?@(:cljs
      [IWatchable
       (-add-watch [this key f] (cond-> this watchable? (add-watch* atom-db key f)))
       (-remove-watch [_ key] (remove-watch atom-db key))
       (-notify-watches [_ old new] (-notify-watches atom-db old new))]

      :default
      [IRef
       (addWatch [this key f] (cond-> this watchable? (add-watch* atom-db key f)))
       (removeWatch [_ key] (remove-watch atom-db key))]))

(deftype WatchableStore [ctx-map atom-db api ->Sub]
  IDeref
  (#?(:cljs -deref :default deref) [_] @atom-db)

  IStore
  (-dispatch! [this command]
    (let [{:keys [command-handler event-reducer]} api]
      (command-handler (assoc ctx-map :defacto.core/store this)
                       command
                       (fn [event]
                         (swap! atom-db event-reducer event)
                         nil))))
  (-subscribe [_ query]
    (->Sub atom-db query))

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
  "Use this to construct a [[DefactoStore]]"
  [ctx-map init-db api ->sub]
  (let [->Sub #(->StandardSubscription %1 %2 (:query-responder api) false)
        base-store (->WatchableStore ctx-map (atom init-db) api ->Sub)]
    (->DefactoStore base-store (->query-cached-sub-fn ->sub) api)))
