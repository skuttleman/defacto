(ns defacto.resources.impl
  (:require
    [clojure.core.async :as async]
    [clojure.core.async.impl.protocols :as iasync]
    [defacto.core :as defacto]))

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

(defn ^:private send-all [send-fn messages result ->output]
  (run! send-fn (for [msg messages]
                  (conj msg (->output result)))))

(defn request!
  [{::defacto/keys [store] ::keys [request-fn]} input emit-cb]
  (let [{:keys [params pre-events pre-commands resource-type]} input
        {:keys [->err ->ok] :or {->err identity ->ok identity}} input
        dispatch-cb (partial defacto/dispatch! store)]
    (run! emit-cb pre-events)
    (run! dispatch-cb pre-commands)
    (when (some? params)
      (let [{:keys [ok-events ok-commands err-events err-commands]} input
            ch (->ch (safely! request-fn resource-type params))]
        (async/go
          (let [[status payload] (->result (async/<! ch))
                [events commands ->output] (if (= :defacto.resources.core/ok status)
                                             [ok-events ok-commands ->ok]
                                             [err-events err-commands ->err])]
            (send-all emit-cb events payload ->output)
            (send-all dispatch-cb commands payload ->output)))))
    nil))
