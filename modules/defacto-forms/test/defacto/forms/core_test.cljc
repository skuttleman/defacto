(ns defacto.forms.core-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [defacto.core :as defacto]
    [defacto.forms.core :as forms]))

(deftest forms-test
  (testing "when creating a form"
    (let [store (defacto/create nil nil)]
      (defacto/dispatch! store [::forms/ensure! 123 {:fruit :apple}])
      (testing "and when querying the db"
        (testing "has the form data"
          (is (= {:fruit :apple} (forms/data (defacto/query-responder @store [::forms/?:form 123]))))))

      (testing "and when recreating a form"
        (defacto/dispatch! store [::forms/ensure! 123 {:random? true}])
        (testing "and when querying the db"
          (testing "retains the original form data"
            (is (= {:fruit :apple} (forms/data (defacto/query-responder @store [::forms/?:form 123])))))))

      (testing "and when updating the form"
        (defacto/emit! store [::forms/changed 123 [:fruit] :banana])
        (defacto/emit! store [::forms/changed 123 [:nested :prop] -13])
        (testing "has the updated form data"
          (is (= {:fruit  :banana
                  :nested {:prop -13}}
                 (forms/data (defacto/query-responder @store [::forms/?:form 123]))))))

      (testing "and when updating the form with an ^int key"
        (defacto/emit! store [::forms/changed 123 [:nested :thing 1 :name] :first])
        (testing "updates the data with a vector"
          (is (= {:fruit  :banana
                  :nested {:prop -13
                           :thing [nil {:name :first}]}}
                 (forms/data (defacto/query-responder @store [::forms/?:form 123]))))))

      (testing "and when destroying the form"
        (defacto/emit! store [::forms/destroyed 123])

        (testing "no longer has form data"
          (is (nil? (forms/data (defacto/query-responder @store [::forms/?:form 123])))))))))
