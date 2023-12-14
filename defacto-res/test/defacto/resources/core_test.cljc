(ns defacto.resources.core-test
  #?(:cljs (:require-macros defacto.resources.core-test))
  (:require
    [clojure.core.async :as async]
    [clojure.test :refer [deftest is testing]]
    [defacto.core :as defacto]
    [defacto.resources.core :as res]
    [defacto.test.utils :as tu]))

(defmethod res/->request-spec ::resource
  [_ params]
  {:params params})

(defmethod defacto/command-handler ::command!
  [_ _ _])

(defmethod defacto/event-reducer ::resourced
  [db [_ value]]
  (assoc db ::resource value))

(deftest resources-test
  (tu/async done
    (async/go
      (let [calls (atom [])
            request-fn (fn [_ req]
                         (swap! calls conj [::request-fn req])
                         [:ok {:some :data}])
            store (defacto/create {:defacto.resources.impl/request-fn request-fn} nil)]
        (testing "when ensuring the resource exists"
          (testing "and when the resource does not exist"
            (defacto/dispatch! store [::res/ensure! [::resource 123] {:some :params}])
            (testing "submits the resource"
              (is (contains? (set @calls) [::request-fn {:some :params}]))))

          (testing "and when the resource exists"
            (reset! calls [])
            (defacto/dispatch! store [::res/ensure! [::resource 123] {:some :params}])
            (testing "does not submit the resource"
              (is (empty? @calls)))))

        (testing "when submitting an existing resource"
          (reset! calls [])
          (defacto/dispatch! store [::res/submit! [::resource 123] {:some :params}])
          (testing "submits the resource"
            (is (= @calls [[::request-fn {:some :params}]]))))

        (testing "when delaying a resource"
          (reset! calls [])
          (defacto/dispatch! store [::res/delay! 100 [::res/submit! [::resource 123] {:some :params}]])
          (testing "and when the delay has not expired"
            (async/<! (async/timeout 50))
            (testing "does not submit the resource"
              (is (empty? @calls))))

          (testing "and when the delay has expired"
            (async/<! (async/timeout 51))
            (testing "submits the resource"
              (is (= @calls [[::request-fn {:some :params}]])))))

        (testing "when syncing a resource"
          (testing "and when the resource has not been requested"
            (reset! calls [])
            (defacto/emit! store [::res/destroyed [::resource 123]])
            (defacto/dispatch! store [::res/sync! [::resource 123] {:some :params}])

            (testing "submits the resource"
              (is (= @calls [[::request-fn {:some :params}]]))))

          (testing "and when the resource is being re-requested with the same params"
            (reset! calls [])
            (defacto/dispatch! store [::res/sync! [::resource 123] {:some :params}])
            (testing "does not submit the resource"
              (is (empty? @calls))))

          (testing "and when the resource is being re-requested with different params"
            (reset! calls [])
            (defacto/dispatch! store [::res/sync! [::resource 123] {:different :params}])
            (testing "submits the resource"
              (is (= @calls [[::request-fn {:different :params}]])))))

        (testing "when polling a resource"
          (reset! calls [])
          (defacto/dispatch! store [::res/poll! 50 [::resource 123] {:some :params}])
          (testing "submits the resource"
            (is (= @calls [[::request-fn {:some :params}]])))

          (testing "and when the poll period has elapsed"
            (reset! calls [])
            (async/<! (async/timeout 51))
            (testing "re-submits the resource"
              (is (= @calls [[::request-fn {:some :params}]]))))

          (testing "and when the resource is destroyed"
            (defacto/emit! store [::res/destroyed [::resource 123]])
            (testing "and when the poll period has elapsed"
              (reset! calls [])
              (async/<! (async/timeout 51))
              (testing "does not re-submit the resource"
                (is (empty? @calls))))))

        (testing "when a resource succeeds"
          (testing "and when the resource is known"
            (defacto/dispatch! store [::res/submit! [::resource 123]])
            (defacto/emit! store [::res/succeeded [::resource 123] {:some :data}])
            (testing "and when querying the resource"
              (let [{:keys [payload status]} (defacto/query-responder @store [::res/?:resource [::resource 123]])]
                (testing "returns the successful resource"
                  (is (= :success status))
                  (is (= {:some :data} payload))))))

          (testing "and when the resource is unknown"
            (defacto/emit! store [::res/destroyed [::resource 123]])
            (defacto/emit! store [::res/succeeded [::resource 123] {:some :data}])
            (testing "and when querying the resource"
              (let [{:keys [payload status]} (defacto/query-responder @store [::res/?:resource [::resource 123]])]
                (testing "returns an initialized resource"
                  (is (= :init status))
                  (is (nil? payload)))))))

        (testing "when a resource fails"
          (testing "and when the resource is submitted"
            (defacto/dispatch! store [::res/submit! [::resource 123]])
            (defacto/emit! store [::res/failed [::resource 123] {:some :data}])
            (testing "and when querying the resource"
              (let [{:keys [payload status]} (defacto/query-responder @store [::res/?:resource [::resource 123]])]
                (testing "returns the successful resource"
                  (is (= :error status))
                  (is (= {:some :data} payload))))))

          (testing "and when the resource is unsubmitted"
            (defacto/emit! store [::res/destroyed [::resource 123]])
            (defacto/emit! store [::res/failed [::resource 123] {:some :data}])
            (testing "and when querying the resource"
              (let [{:keys [payload status]} (defacto/query-responder @store [::res/?:resource [::resource 123]])]
                (testing "returns an initialized resource"
                  (is (= :init status))
                  (is (nil? payload))))))

          (testing "and when the resource is unknown"
            (testing "and when querying the resource"
              (let [resource (defacto/query-responder @store [::res/?:resource ::none])]
                (testing "returns an initialized resource"
                  (is (nil? resource)))))))

        (done)))))
