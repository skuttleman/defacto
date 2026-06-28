(ns defacto.resources.impl
  (:require
    [clojure.core.async :as async]
    [clojure.core.async.impl.protocols :as iasync]
    [defacto.core :as defacto]
    [defacto.resources.async :as res.async]))

(defn ^:private safely! [request-fn & args]
  (try
    (apply request-fn args)
    (catch #?(:cljs :default :default Throwable) ex
      [:defacto.resources.core/err {:exception ex
                                    :reason    "request-fn threw an exception"}])))

(defn ^:private ->ch [ch-or-result]
  (async/go
    (cond-> ch-or-result
      (satisfies? iasync/ReadPort ch-or-result)
      async/<!)))

(defn ^:private ->result [result]
  (if (vector? result)
    result
    [:defacto.resources.core/err {:result result
                                  :reason "request-fn must return a vector"}]))

(defn ^:private cbs [messages output]
  (for [msg (distinct messages)]
    (conj msg output)))

(defn ^:private ->upload-progress-ch [{:keys [prog-events prog-commands]} emit-cb dispatch-cb]
  (when (or (seq prog-events) (seq prog-commands))
    (let [chan (async/chan)]
      (async/go-loop []
        (let [report (async/<! chan)]
          (if (= :upload (:direction report))
            (do
              (run! emit-cb (cbs prog-events report))
              (run! dispatch-cb (cbs prog-commands report))
              (recur))
            (do
              (run! emit-cb (cbs prog-events {:status :complete}))
              (run! dispatch-cb (cbs prog-commands {:status :complete}))
              (async/close! chan)))))
      chan)))

(defn request!
  [{::defacto/keys [store] ::keys [request-fn]} input emit-cb]
  (let [{:keys [params pre-events pre-commands resource-type]} input
        {:keys [->err ->ok] :or {->err identity ->ok identity}} input
        dispatch-cb (partial defacto/dispatch! store)]
    (run! emit-cb pre-events)
    (run! dispatch-cb pre-commands)
    (when (some? params)
      (let [{:keys [async? ok-events ok-commands err-events err-commands]} input
            request-id (random-uuid)
            params (-> params
                       (assoc :progress (->upload-progress-ch input emit-cb dispatch-cb))
                       (assoc-in [:headers "x-request-id"] request-id))
            ch (->ch (safely! request-fn resource-type params))]
        (async/go
          (when async?
            (emit-cb [::res.async/-registered request-id ok-commands ok-events ->ok]))
          (let [[status payload] (->result (async/<! ch))
                [events commands ->output] (cond
                                             (not= :defacto.resources.core/ok status)
                                             [err-events err-commands ->err]

                                             (not async?)
                                             [ok-events ok-commands ->ok])
                output (when ->output (->output payload))]
            (run! emit-cb (cbs events output))
            (run! dispatch-cb (cbs commands output))
            (when async?
              (async/<! (async/timeout (:timeout input 20000)))
              (dispatch-cb [::res.async/-timeout! request-id err-commands err-events]))))))
    nil))
