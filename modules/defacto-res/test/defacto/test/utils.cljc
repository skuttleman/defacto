(ns defacto.test.utils
  #?(:cljs (:require cljs.test)))

(defmacro async [cb & body]
  (if (:ns &env)
    `(cljs.test/async ~cb ~@body)
    `(let [prom# (promise)
           ~cb (fn [] (deliver prom# true))
           result# (do ~@body)]
       @prom#
       result#)))
