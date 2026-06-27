(ns defacto.impl
  #?(:clj
     (:import
      (clojure.lang IDeref IMeta IRef)
      (java.lang.ref Cleaner WeakReference))))

(defonce ^:private cleaner
         #?(:clj  (Cleaner/create)
            :cljs (js/FinalizationRegistry. (fn [cb] (cb)))))

(defprotocol IStore
  "A store for processing `commands` and `events`. A `command` is used to invoke side
   effects, which may include emitting `events`."
  (-dispatch! [this command]
    "Dispatches a `command` through the system causing potential side effects and/or emitting `events`
     which update the internal db.")
  (-subscribe [this query]
    "Returns a deref-able and watchable subscription to a `query`.
     The subscription should be updated any time the query results change."))

(defn ^:private add-watch* [this sub key f]
  (add-watch sub key (fn [key _ old new]
                       (f key this old new)))
  this)

(defn ^:private remove-watch* [this sub key]
  (remove-watch sub key)
  this)

#?(:cljs
   (defn ^:private notify-watches* [this sub old new]
     (-notify-watches sub old new)
     this))

(defn ^:private ->watcher [responder query weak-ref]
  (fn [key store old new]
    (let [prev (responder old query)
          next (responder new query)]
      (if-let [sub #?(:cljs (.deref weak-ref) :default (.get weak-ref))]
        (when-not (= prev next)
          (reset! sub next))
        (remove-watch store key)))))

(deftype ^:private ImmutableSubscription [sub meta]
  IDeref
  (#?(:cljs -deref :default deref) [_] @sub)

  IMeta
  (#?(:cljs -meta :default meta) [_] meta)

  #?@(:cljs
      [IWatchable
       (-add-watch [this key f] (add-watch* this sub key f))
       (-remove-watch [this key] (remove-watch* this sub key))
       (-notify-watches [this old new] (notify-watches* this sub old new))]

      :default
      [IRef
       (addWatch [this key f] (add-watch* this sub key f))
       (removeWatch [this key] (remove-watch* this sub key))]))

(deftype DefactoStore [watchable-store ->atom-sub api]
  IDeref
  (#?(:cljs -deref :default deref) [_] @watchable-store)

  IStore
  (-dispatch! [this command]
    (-dispatch! watchable-store command)
    this)
  (-subscribe [_ query]
    (let [responder (:query-responder api)
          key (gensym "watch-key-")
          on-cleanup (fn [] (remove-watch watchable-store key))
          sub (->atom-sub (responder @watchable-store query))
          weak-ref #?(:cljs (js/WeakRef. sub) :default (WeakReference. sub))]
      (add-watch watchable-store key (->watcher responder query weak-ref))
      (doto (->ImmutableSubscription sub {::key key})
        (as-> $ (.register cleaner $ on-cleanup))))))

(deftype StandardSubscription [atom-db query responder]
  IDeref
  (#?(:cljs -deref :default deref) [_] (responder @atom-db query))

  #?@(:cljs
      [IWatchable
       (-add-watch [this key f] (add-watch* this atom-db key f))
       (-remove-watch [this key] (remove-watch* this atom-db key))
       (-notify-watches [this old new] (notify-watches* this atom-db old new))]

      :default
      [IRef
       (addWatch [this key f] (add-watch* this atom-db key f))
       (removeWatch [this key] (remove-watch* this atom-db key))]))

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
       (-remove-watch [this key] (remove-watch* this atom-db key))
       (-notify-watches [this old new] (notify-watches* this atom-db old new))]

      :default
      [IRef
       (addWatch [this key f] (add-watch* this atom-db key f))
       (removeWatch [this key] (remove-watch* this atom-db key))]))

(defn create
  "Use this to construct a [[DefactoStore]]"
  [ctx-map init-db api ->sub]
  (let [->Sub #(->StandardSubscription %1 %2 (:query-responder api))
        base-store (->WatchableStore ctx-map (atom init-db) api ->Sub)]
    (->DefactoStore base-store ->sub api)))
