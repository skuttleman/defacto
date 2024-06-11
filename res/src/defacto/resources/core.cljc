(ns defacto.resources.core
  (:require
    [clojure.core.async :as async]
    [defacto.core :as defacto]
    [defacto.resources.impl :as impl]))

(defmulti ^{:arglists '([resource-key params])} ->request-spec
          "Implement this to generate a request spec from a resource-key.
           Your implementation should return a map containing any of the following:

           - :params       - passed to the request fn

           - :pre-events   - xs of events to emitted before the request is made
           - :pre-commands - xs of commands to dispatched before the request is made
           - :ok-events    - xs of events to emitted upon success with the payload conj'ed onto the event
           - :ok-commands  - xs of commands to dispatched upon success with the payload conj'ed onto the command
           - :err-events   - xs of events to emitted upon failure with the payload conj'ed onto the event
           - :err-commands - xs of commands to dispatched upon failure with the payload conj'ed onto the command

           - :->ok         - optionally transform the ok response payload
           - :->err        - optionally transform the error response payload"
          (fn [resource-key _]
            (first resource-key)))

(defn with-ctx
  ([request-fn]
   (with-ctx {} request-fn))
  ([ctx-map request-fn]
   (assoc ctx-map ::impl/request-fn request-fn)))

(defn init? [resource]
  (= :init (::status resource)))

(defn success? [resource]
  (= :success (::status resource)))

(defn error? [resource]
  (= :error (::status resource)))

(defn requesting? [resource]
  (= :requesting (::status resource)))

(defn payload [resource]
  (::payload resource))

(defn params [resource]
  (::params resource))

(defn ^:private with-msgs [m k spec]
  (if-let [v (seq (get spec k))]
    (update m k (fnil into []) v)
    m))

(defn ^:private ->input [resource-key {:keys [params] :as spec}]
  (-> (->request-spec resource-key params)
      (assoc :resource-type (first resource-key)
             :spec spec)
      (with-msgs :pre-events spec)
      (with-msgs :pre-commands spec)
      (with-msgs :ok-events spec)
      (with-msgs :ok-commands spec)
      (with-msgs :err-events spec)
      (with-msgs :err-commands spec)))

(defn ^:private params->spec [resource-key params]
  {:params     params
   :ok-events  [[::succeeded resource-key]]
   :err-events [[::failed resource-key]]})


;; commands
(defmethod defacto/command-handler ::delay!
  [{::defacto/keys [store]} [_ ms command] _]
  (async/go
    (async/<! (async/timeout ms))
    (defacto/dispatch! store command)))

(defmethod defacto/command-handler ::submit!
  [ctx-map [_ resource-key params] emit-cb]
  (let [spec (params->spec resource-key params)]
    (emit-cb [::submitted resource-key params])
    (impl/request! ctx-map (->input resource-key spec) emit-cb)))

(defmethod defacto/command-handler ::ensure!
  [{::defacto/keys [store]} [_ resource-key params] _]
  (when (= :init (::status (defacto/query-responder @store [::?:resource resource-key])))
    (defacto/dispatch! store [::submit! resource-key params])))

(defmethod defacto/command-handler ::sync!
  [{::defacto/keys [store]} [_ resource-key next-params] _]
  (let [{::keys [status params]} (defacto/query-responder @store [::?:resource resource-key])]
    (when (or (= :init status) (not= params next-params))
      (defacto/dispatch! store [::submit! resource-key next-params]))))

(defmethod defacto/command-handler ::poll!
  [{::defacto/keys [store] :as ctx-map} [_ ms resource-key params when-exists?] emit-cb]
  (when (or (not when-exists?) (get-in @store [::-resources resource-key]))
    (let [spec (-> (params->spec resource-key params)
                   (assoc :ok-commands [[::delay! ms [::poll! ms resource-key params true]]]
                          :err-commands [[::delay! ms [::poll! ms resource-key params true]]]))]
      (emit-cb [::submitted resource-key params])
      (impl/request! ctx-map (->input resource-key spec) emit-cb))))


;; queries
(defmethod defacto/query-responder ::?:resources
  [db _]
  (vals (::-resources db)))

(defmethod defacto/query-responder ::?:resource
  [db [_ resource-key]]
  (or (get-in db [::-resources resource-key])
      (when (contains? (methods ->request-spec) (first resource-key))
        {::status :init})))


;; events
(defmethod defacto/event-reducer ::submitted
  [db [_ resource-key params]]
  (update-in db [::-resources resource-key] assoc
             ::status :requesting
             ::params params))

(defmethod defacto/event-reducer ::succeeded
  [db [_ resource-key data]]
  (cond-> db
    (= :requesting (get-in db [::-resources resource-key ::status]))
    (update-in [::-resources resource-key] assoc
               ::status :success
               ::payload data)))

(defmethod defacto/event-reducer ::failed
  [db [_ resource-key errors]]
  (cond-> db
    (= :requesting (get-in db [::-resources resource-key ::status]))
    (update-in [::-resources resource-key] assoc
               ::status :error
               ::payload errors)))

(defmethod defacto/event-reducer ::destroyed
  [db [_ resource-key]]
  (update db ::-resources dissoc resource-key))

(defmethod defacto/event-reducer ::swapped
  [db [_ resource-key data]]
  (cond-> db
    (= :success (get-in db [::-resources resource-key ::status]))
    (assoc-in [::-resources resource-key ::payload] data)))
