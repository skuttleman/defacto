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
                         [::res/ok {:some :data}])
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
              (is (empty? @calls))))

          (testing "and when ensuring the resource was not requested recently"
            (async/<! (async/timeout 500))
            (testing "and when ensuring the resource exists with a ttl"
              (reset! calls [])
              (defacto/dispatch! store [::res/ensure! [::resource 123] {::res/ttl 500 :some :params}])
              (testing "submits the resource"
                (is (contains? (set @calls) [::request-fn {:some :params}]))))))

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
              (let [resource (defacto/query-responder @store [::res/?:resource [::resource 123]])]
                (testing "returns the successful resource"
                  (is (res/success? resource))
                  (is (= {:some :data} (res/payload resource)))))))

          (testing "and when the resource is unknown"
            (defacto/emit! store [::res/destroyed [::resource 123]])
            (defacto/emit! store [::res/succeeded [::resource 123] {:some :data}])
            (testing "and when querying the resource"
              (let [resource (defacto/query-responder @store [::res/?:resource [::resource 123]])]
                (testing "returns an initialized resource"
                  (is (res/init? resource))
                  (is (nil? (res/payload resource))))))))

        (testing "when a resource fails"
          (testing "and when the resource is submitted"
            (defacto/dispatch! store [::res/submit! [::resource 123]])
            (defacto/emit! store [::res/failed [::resource 123] {:some :data}])
            (testing "and when querying the resource"
              (let [resource (defacto/query-responder @store [::res/?:resource [::resource 123]])]
                (testing "returns the successful resource"
                  (is (res/error? resource))
                  (is (= {:some :data} (res/payload resource)))))))

          (testing "and when the resource is unsubmitted"
            (defacto/emit! store [::res/destroyed [::resource 123]])
            (defacto/emit! store [::res/failed [::resource 123] {:some :data}])
            (testing "and when querying the resource"
              (let [resource (defacto/query-responder @store [::res/?:resource [::resource 123]])]
                (testing "returns an initialized resource"
                  (is (res/init? resource))
                  (is (nil? (res/payload resource)))))))

          (testing "and when the resource is unknown"
            (testing "and when querying the resource"
              (let [resource (defacto/query-responder @store [::res/?:resource [::none]])]
                (testing "returns an initialized resource"
                  (is (nil? resource)))))))

        (done)))))
