(ns defacto.resources.async
  (:require
   [defacto.core :as defacto]))

(def ^:private ^:const timeout-err
  {::defacto/reason "Response was not received within timeout"})


;; commands
(defmethod defacto/command-handler ::receive!
  [{::defacto/keys [store]} [_ request-id result] emit-cb]
  (when-let [{:keys [commands events ->output]} (get-in @store [::-async request-id])]
    (let [output (->output result)]
      (emit-cb [::-deregistered request-id])
      (run! emit-cb (for [event events]
                      (conj event output)))
      (reduce defacto/dispatch! store (for [command commands]
                                        (conj command output))))))


;; internal
(defmethod defacto/command-handler ::-timeout!
  [{::defacto/keys [store]} [_ request-id commands events] emit-cb]
  (when (get-in @store [::-async request-id])
    (emit-cb [::-deregistered request-id])
    (run! emit-cb (for [event events]
                    (conj event timeout-err)))
    (reduce defacto/dispatch! store (for [command commands]
                                      (conj command timeout-err)))))

(defmethod defacto/event-reducer ::-registered
  [db [_ request-id commands events ->output]]
  (assoc-in db [::-async request-id] {:->output (or ->output identity)
                                      :commands commands
                                      :events   events}))

(defmethod defacto/event-reducer ::-deregistered
  [db [_ request-id]]
  (update db ::-async dissoc request-id))
