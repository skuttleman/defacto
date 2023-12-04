(ns defacto.core-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [defacto.core :as defacto]))

(defmethod defacto/command-handler ::command!
  [_ [_ params] emit-cb]
  (emit-cb [::commanded params]))

(defmethod defacto/event-handler ::commanded
  [db [_ result]]
  (assoc db ::result result))

(defmethod defacto/query ::result?
  [db _]
  (::result db))

(defmethod defacto/command-handler ::another!
  [_ [_ params] emit-cb]
  (emit-cb [::anothered params]))

(defmethod defacto/event-handler ::anothered
  [db [_ result]]
  (assoc db ::something-else result))

(defmethod defacto/query ::something-else?
  [db _]
  (::something-else db))

(deftest DefactoStore-test
  (let [store (defacto/->DefactoStore {} (atom {}) atom)
        result (defacto/subscribe store [::result?])
        something-else (defacto/subscribe store [::something-else?])
        notifications (atom [])]
    (testing "when dispatching a command"
      (defacto/dispatch! store [::command! #{:apple :banana}])
      (testing "processes updates"
        (is (= #{:apple :banana} @result))))

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
          (defacto/dispatch! store [::another! {:some :value}])
          (testing "processes the command"
            (is (= {:some :value} @something-else)))

          (testing "does not notify the watcher"
            (is (empty? @notifications))))))))
