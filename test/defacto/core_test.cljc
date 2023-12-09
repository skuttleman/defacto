(ns defacto.core-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [defacto.core :as defacto]))

(defmethod defacto/command-handler ::command!
  [_ [_ params] emit-cb]
  (emit-cb [::commanded params]))

(defmethod defacto/event-reducer ::commanded
  [db [_ result]]
  (assoc db ::result result))

(defmethod defacto/query-responder ::result
  [db _]
  (::result db))

(defmethod defacto/command-handler ::change!
  [_ [_ params] emit-cb]
  (emit-cb [::changed params]))

(defmethod defacto/event-reducer ::changed
  [db [_ result]]
  (assoc db ::something-else result))

(defmethod defacto/query-responder ::something-else
  [db _]
  (::something-else db))

(deftest DefactoStore-test
  (let [store (defacto/create {} {} atom)
        result (defacto/subscribe store [::result])
        something-else (defacto/subscribe store [::something-else])
        notifications (atom [])]
    (testing "when dispatching a command"
      (defacto/dispatch! store [::command! #{:apple :banana}])
      (testing "processes updates"
        (is (= #{:apple :banana} @result))))

    (testing "when emitting an event"
      (defacto/emit! store [::commanded #{:orange :pineapple}])
      (testing "processes updates"
        (is (= #{:orange :pineapple} @result))))

    (testing "when watching a subscription"
      (add-watch result ::watch (fn [_ _ _ new]
                                  (swap! notifications conj new)))
      (testing "and when the query result changes"
        (defacto/dispatch! store [::command! #{:pear :kiwi}])
        (testing "processes the command"
          (is (= #{:pear :kiwi} @result)))

        (testing "notifies the watcher"
          (is (= [#{:pear :kiwi}] @notifications)))

        (testing "and when a state change does not change the query result"
          (reset! notifications [])
          (defacto/dispatch! store [::change! {:some :value}])
          (testing "processes the command"
            (is (= {:some :value} @something-else)))

          (testing "does not notify the watcher"
            (is (empty? @notifications))))))

    (testing "when including an initializer in the context map"
      (let [store-prom (promise)
            store (defacto/create {:my-component (reify
                                                   defacto/IInitialize
                                                   (init! [_ store] (deliver store-prom store)))}
                                  nil)]
        (testing "initializes the component"
          (is (= store @store-prom)))))))
