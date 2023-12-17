(ns defacto.forms.plus-test
  (:require
    [clojure.core.async :as async]
    [clojure.test :refer [deftest is testing]]
    [defacto.core :as defacto]
    [defacto.forms.core :as forms]
    [defacto.forms.plus :as forms+]
    [defacto.resources.core :as res]))

(defmacro async [cb & body]
  (if (:ns &env)
    `(cljs.test/async ~cb ~@body)
    `(let [prom# (promise)
           ~cb (fn [] (deliver prom# :done))
           result# (do ~@body)]
       @prom#
       result#)))

(defmethod forms+/re-init ::res-spec [_ _ _]
  {:init true})

(defmethod res/->request-spec ::res-spec
  [_ {::forms/keys [data]}]
  {:params data})

(deftest std-form-test
  (async done
    (async/go
      (testing "when creating a form+"
        (let [resp (atom [::res/err {:some "error"}])
              store (defacto/create (res/with-ctx (fn [_ _] @resp)) nil)
              sub:form+ (defacto/subscribe store [::forms+/?:form+ [::forms+/std [::res-spec]]])]
          (defacto/emit! store [::forms/created [::forms+/std [::res-spec]] {:some {:path "init-value"}}])
          (testing "and when modifying the form+"
            (-> store
                (defacto/emit! [::forms/changed [::forms+/std [::res-spec]] [:some :path] "new-value"])
                (defacto/emit! [::forms/changed [::forms+/std [::res-spec]] [:another :path] "value"]))

            (testing "has the form data"
              (is (= {:some    {:path "new-value"}
                      :another {:path "value"}}
                     (forms/data @sub:form+))))

            (testing "and when submitting the form+"
              (defacto/dispatch! store [::forms+/submit! [::forms+/std [::res-spec]]])
              (async/<! (async/timeout 5))

              (testing "and when the submission fails"
                (testing "does not re-initialize the form+"
                  (is (= {:some    {:path "new-value"}
                          :another {:path "value"}}
                         (forms/data @sub:form+))))

                (testing "is in an `error` state"
                  (is (res/error? @sub:form+))
                  (is (= {:some "error"}
                         (res/payload @sub:form+)))))

              (testing "and when the submission succeeds"
                (reset! resp [::res/ok {:a :ok}])
                (defacto/dispatch! store [::forms+/submit! [::forms+/std [::res-spec]]])
                (async/<! (async/timeout 5))

                (testing "re-initializes the form+"
                  (is (= {:init true}
                         (forms/data @sub:form+))))

                (testing "is in a `success` state"
                  (is (res/success? @sub:form+))
                  (is (= {:a :ok} (res/payload @sub:form+)))))))))
      (done))))

(defmethod forms+/validate ::res-spec
  [_ form-data]
  (when (:fail? form-data)
    {:bad ["errors"]}))

(deftest valid-form-test
  (async done
    (async/go
      (testing "when creating a form+"
        (let [resp (atom [::res/err {:some "error"}])
              store (defacto/create (res/with-ctx (fn [_ _] @resp)) nil)
              sub:form+ (defacto/subscribe store [::forms+/?:form+ [::forms+/valid [::res-spec]]])]
          (defacto/emit! store [::forms/created [::forms+/valid [::res-spec]]])
          (testing "and when modifying the form+"
            (defacto/emit! store [::forms/changed [::forms+/valid [::res-spec]] [:fail?] true])

            (testing "has the form data"
              (is (= {:fail? true} (forms/data @sub:form+))))

            (testing "and when submitting the form+"
              (defacto/dispatch! store [::forms+/submit! [::forms+/valid [::res-spec]]])

              (testing "and when the validation fails"
                (testing "does not re-initialize the form+"
                  (is (= {:fail? true} (forms/data @sub:form+))))

                (testing "is in an `error` state"
                  (is (res/error? @sub:form+))
                  (is (= {::forms/errors {:bad ["errors"]}} (res/payload @sub:form+)))))

              (testing "and when the validation succeeds"
                (defacto/emit! store [::forms/changed [::forms+/valid [::res-spec]] [:fail?] false])
                (defacto/dispatch! store [::forms+/submit! [::forms+/valid [::res-spec]]])
                (async/<! (async/timeout 5))

                (testing "and when the submission fails"
                  (testing "does not re-initialize the form+"
                    (is (= {:fail? false} (forms/data @sub:form+))))

                  (testing "is in an `error` state"
                    (is (res/error? @sub:form+))
                    (is (= {:some "error"}
                           (res/payload @sub:form+)))))

                (testing "and when the submission succeeds"
                  (reset! resp [::res/ok {:a :ok}])
                  (defacto/dispatch! store [::forms+/submit! [::forms+/valid [::res-spec]]])
                  (async/<! (async/timeout 5))

                  (testing "re-initializes the form+"
                    (is (= {:init true} (forms/data @sub:form+))))

                  (testing "is in a `success` state"
                    (is (res/success? @sub:form+))
                    (is (= {:a :ok} (res/payload @sub:form+))))))))))

      (done))))
