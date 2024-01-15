(ns defacto.resources.impl-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [clojure.core.async :as async]
    [defacto.core :as defacto]
    [defacto.test.utils :as tu]
    [defacto.resources.core :as res]
    [defacto.resources.impl :as impl]))

(def ^:private fixture
  {:params       {:some :params}
   :pre-events   [[::pre'ed-1]
                  [::pre'ed-2]]
   :pre-commands [[::pre-1!]
                  [::pre-2!]]
   :ok-events    [[::oked-1]
                  [::oked-2]]
   :ok-commands  [[::ok-1!]
                  [::ok-2!]]
   :err-events   [[::erred-1]
                  [::erred-2]]
   :err-commands [[::err-1!]
                  [::err-2!]]
   :->ok         vector
   :->err        (partial conj #{})})

(deftest request!-test
  (tu/async done
    (async/go
      (let [commands (atom [])
            events (atom [])
            store (reify
                    defacto.impl/IStore
                    (-dispatch! [_ command]
                      (swap! commands conj command)))
            ctx-map {::defacto/store   store
                     ::impl/request-fn (fn [_ _]
                                         (async/timeout 1000))}
            emit-cb (partial swap! events conj)]
        (testing "when requesting a resource"
          (impl/request! ctx-map fixture emit-cb)
          (testing "emits pre-events"
            (is (= [[::pre'ed-1] [::pre'ed-2]] @events)))

          (testing "dispatches pre-commands"
            (is (= [[::pre-1!] [::pre-2!]] @commands)))

          (testing "and when the request succeeds"
            (let [ctx-map (assoc ctx-map ::impl/request-fn (fn [_ _]
                                                             (async/go
                                                               (reset! commands [])
                                                               (reset! events [])
                                                               [::res/ok {:some :data}])))]
              (impl/request! ctx-map fixture emit-cb)
              (async/<! (async/timeout 5))
              (testing "emits ok-events"
                (is (= [[::oked-1 [{:some :data}]]
                        [::oked-2 [{:some :data}]]]
                       @events)))

              (testing "dispatches ok-commands"
                (is (= [[::ok-1! [{:some :data}]]
                        [::ok-2! [{:some :data}]]]
                       @commands)))))

          (testing "and when the request fails"
            (let [ctx-map (assoc ctx-map ::impl/request-fn (fn [_ _]
                                                             (async/go
                                                               (reset! commands [])
                                                               (reset! events [])
                                                               [::res/err {:some :err}])))]
              (impl/request! ctx-map fixture emit-cb)
              (async/<! (async/timeout 5))
              (testing "emits err-events"
                (is (= [[::erred-1 #{{:some :err}}]
                        [::erred-2 #{{:some :err}}]]
                       @events)))

              (testing "dispatches err-commands"
                (is (= [[::err-1! #{{:some :err}}]
                        [::err-2! #{{:some :err}}]]
                       @commands)))))

          (testing "and when the request-fn does not return a vector"
            (let [ctx-map (assoc ctx-map ::impl/request-fn (fn [_ _]
                                                             (async/go
                                                               (reset! commands [])
                                                               (reset! events [])
                                                               {:some :data})))]
              (impl/request! ctx-map fixture emit-cb)
              (async/<! (async/timeout 5))
              (testing "emits err-events"
                (is (= [[::erred-1 #{{:result {:some :data}
                                      :reason "request-fn must return a vector"}}]
                        [::erred-2 #{{:result {:some :data}
                                      :reason "request-fn must return a vector"}}]]
                       @events)))

              (testing "dispatches err-commands"
                (is (= [[::err-1! #{{:result {:some :data}
                                     :reason "request-fn must return a vector"}}]
                        [::err-2! #{{:result {:some :data}
                                     :reason "request-fn must return a vector"}}]]
                       @commands)))))

          (testing "and when the request-fn does not return a channel"
            (testing "and when the request succeeds"
              (let [ctx-map (assoc ctx-map ::impl/request-fn (fn [_ _]
                                                               (reset! commands [])
                                                               (reset! events [])
                                                               [::res/ok {:some :data}]))]
                (impl/request! ctx-map fixture emit-cb)
                (async/<! (async/timeout 5))
                (testing "emits ok-events"
                  (is (= [[::oked-1 [{:some :data}]]
                          [::oked-2 [{:some :data}]]]
                         @events)))

                (testing "dispatches ok-commands"
                  (is (= [[::ok-1! [{:some :data}]]
                          [::ok-2! [{:some :data}]]]
                         @commands)))))

            (testing "and when the request fails"
              (let [ctx-map (assoc ctx-map ::impl/request-fn (fn [_ _]
                                                               (reset! commands [])
                                                               (reset! events [])
                                                               {:some :data}))]
                (impl/request! ctx-map fixture emit-cb)
                (async/<! (async/timeout 5))
                (testing "emits err-events"
                  (is (= [[::erred-1 #{{:result {:some :data}
                                        :reason "request-fn must return a vector"}}]
                          [::erred-2 #{{:result {:some :data}
                                        :reason "request-fn must return a vector"}}]]
                         @events)))

                (testing "dispatches err-commands"
                  (is (= [[::err-1! #{{:result {:some :data}
                                       :reason "request-fn must return a vector"}}]
                          [::err-2! #{{:result {:some :data}
                                       :reason "request-fn must return a vector"}}]]
                         @commands))))))

          (testing "and when the request-fn throws an exception"
            (let [ex (ex-info "blammo!" {:some :err})
                  ctx-map (assoc ctx-map ::impl/request-fn (fn [_ _]
                                                             (reset! commands [])
                                                             (reset! events [])
                                                             (throw ex)))]
              (impl/request! ctx-map fixture emit-cb)
              (async/<! (async/timeout 5))
              (testing "emits err-events"
                (is (= [[::erred-1 #{{:exception ex
                                      :reason    "request-fn threw an exception"}}]
                        [::erred-2 #{{:exception ex
                                      :reason    "request-fn threw an exception"}}]]
                       @events)))

              (testing "dispatches err-commands"
                (is (= [[::err-1! #{{:exception ex
                                     :reason    "request-fn threw an exception"}}]
                        [::err-2! #{{:exception ex
                                     :reason    "request-fn threw an exception"}}]]
                       @commands))))))
        (done)))))
