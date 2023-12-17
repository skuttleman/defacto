(ns defacto.forms.plus
  "Additional form facilities that combine [[defacto.forms.core]] and [[defacto.resources.core]]"
  (:require
    [defacto.core :as defacto]
    [defacto.forms.core :as forms]
    [defacto.resources.core :as res]))

(defmulti ^{:arglists '([resource-key form resource-payload])} re-init
          "Extend this multimethod to define how your form is reinitialize upon
           successful submission. Defaults to the initial form value."
          (fn [resource-key _ _]
            (first resource-key)))

(defmulti ^{:arglists '([resource-key form-data])} validate
          "Extend this multimethod for `::valid` forms. Your validator should
           return `nil` when valid, or an appropriate data structure to represent
           form errors."
          (fn [resource-key _form-data]
            (first resource-key)))


;; forms
(defmethod res/->request-spec ::std
  [[_ resource-key :as form-key] {::forms/keys [form] :as params}]
  (let [form-data (forms/data form)]
    (-> (res/->request-spec resource-key (assoc params ::forms/data form-data))
        (update :ok-events conj [::recreated form-key form]))))

(defmethod res/->request-spec ::valid
  [[_ resource-key :as form-key] {::forms/keys [form] :as params}]
  (let [form-data (forms/data form)]
    (if-let [errors (validate resource-key form-data)]
      {:pre-events [[::res/failed form-key {::forms/errors errors}]]}
      (-> (res/->request-spec resource-key (assoc params ::forms/data form-data))
          (update :ok-events conj [::recreated form-key form])))))


;; commands
(defmethod defacto/command-handler ::submit!
  [{::defacto/keys [store]} [_ form-key params] _]
  (let [form (defacto/query-responder @store [::forms/?:form form-key])
        params (cond-> params form (assoc ::forms/form form))]
    (defacto/dispatch! store [::res/submit! form-key params])))


;; queries
(defmethod defacto/query-responder ::?:form+
  [db [_ form-key]]
  (merge (defacto/query-responder db [::forms/?:form form-key])
         (defacto/query-responder db [::res/?:resource form-key])))


;; events
(defmethod defacto/event-reducer ::recreated
  [db [_ [_ resource-key :as form-key] form result]]
  (let [next-init (re-init resource-key form result)]
    (defacto/event-reducer db [::forms/created form-key next-init (forms/opts form)])))

(defmethod defacto/event-reducer ::destroyed
  [db [_ form-key]]
  (-> db
      (defacto/event-reducer [::forms/destroyed form-key])
      (defacto/event-reducer [::res/destroyed form-key])))


;; internal
(defmethod re-init :default
  [_ form _]
  (forms/initial form))
