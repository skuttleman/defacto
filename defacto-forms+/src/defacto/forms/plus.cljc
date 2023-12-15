(ns defacto.forms.plus
  "Additional form facilities that will only work if you are also using [[defacto.resources.core]]"
  (:require
    [defacto.core :as defacto]
    [defacto.forms.core :as forms]
    [defacto.resources.core :as res]))

(defmulti validate
          (fn [resource-key _form-data]
            (first resource-key)))

(defmethod res/->request-spec ::std
  [[_ resource-key] params]
  (res/->request-spec resource-key (dissoc params ::forms/form)))

(defmethod res/->request-spec ::post
  [[_ resource-key :as form-key] {::forms/keys [form] :as params}]
  (let [form-data (forms/data form)]
    (if-let [errors (validate resource-key form-data)]
      {:pre-events [[::res/failed form-key {::forms/errors errors}]]}
      (-> (res/->request-spec resource-key (assoc params ::forms/data form-data))
          (update :ok-events conj
                  [::res/destroyed form-key]
                  [::forms/created form-key (forms/initial form) (forms/opts form)])))))



;; commands
(defmethod defacto/command-handler ::submit!
  [{::defacto/keys [store]} [_ form-key params] _]
  (let [form (defacto/query-responder @store [::forms/?:form form-key])]
    (defacto/dispatch! store [::res/submit! form-key (assoc params ::forms/form form)])))


;; events
(defmethod defacto/event-reducer ::destroyed
  [db [_ form-key]]
  (-> db
      (defacto/event-reducer [::forms/destroyed form-key])
      (defacto/event-reducer [::res/destroyed form-key])))