(ns visuflow.core
  (:require [clojure.string :as string]))

(defn arg-count [f]
  (let [m (first (.getDeclaredMethods (class f)))
        f (if-not (symbol? f) f (eval f))
        f (if (var? f) @f f)
        p (.getParameterTypes m)]
    (if (keyword? f)
      1
      (alength p))))

(def builtin-validargs
  {:data [[(fn[x]
             (not (or (nil? x) (false? x) (true? x)))) 1]
          [:else 2]]
   
   :true [[true? 1]
          [:else 2]]
   
   :false [[false? 1]
           [:else 2]]
   
   :nil [[nil? 1]
         [:else 2]]
   
   :fnil [[(fn[x] (or (false? x) (nil? x))) 1]
          [:else 2]]})

(defn v-is
  [arg validarg-list]
  (reduce (fn [rval [fun ret]]
            (if (= :else fun)
              ret
              (if (true? (apply fun [arg]))
                ret
                rval)))
          nil
          validarg-list))

(defn popstack "Gets values from the stack by pop."
  [{:keys [stack count]}]
  [(take count stack)
   (drop count stack)])

(defn getstack "Gets values from the stack by peek."
  [{:keys [stack count]}]
  [(take count stack)
   stack])

(defn apply-validarg "Applies a validarg to a function result."
  [{:keys [inp varg validargs]}]
  (if-let [is-varg (varg validargs)]
    (v-is inp is-varg)
    (throw (Exception. (str "Validarg does not exist: " varg " in " validargs)))))

(defn contains-cb? "Checks if the given statement contains a cb (except :!f, :!p and :!fp)"
  [coll]
  (when-not (number? coll)
    (if-let [cb (if (or (keyword? coll) (keyword? (first coll)))
                  (if (sequential? coll) (name (first coll)) (name coll)))]
      (if (and (.startsWith cb "!")
             (not= cb "!f") (not= cb "!p") (not= cb "!fp"))
        true false)
      false)))

(defn parse-cb-stmt "Parses and applies one cb. Returns [coll stack]."
  [{:keys [coll cb stack]}]
  (let [cb (if (nil? cb) (first coll) cb)
        [com arg] (string/split (name cb)  #"#")
        arg (when-not (nil? arg) (. Integer (parseInt arg)))]
    (case com
      "!_" [coll (take arg stack)]
      "!d" [(drop arg coll) stack]
      "!q" [nil (first stack)])))

(defn parse-cb-res "Coordinating parsing of a list of validarg statements. Returns [coll stack]."
  [{:keys [coll cbs stack]}]
  (if (sequential? cbs)
    (let [car (first cbs) cdr (rest cbs)]
      (if (or (empty? cdr) (nil? cdr))
        (parse-cb-stmt {:coll coll :cb car :stack stack})
        (let [[coll stack] (parse-cb-stmt {:coll coll :cb car :stack stack})]
          (parse-cb-res {:coll coll :cbs cdr :stack stack}))))
    (parse-cb-stmt {:coll coll :cb cbs :stack stack})))

(defn eval-with-stack "Evaluates a function using values from the stack."
  [{:keys [pop? car args stack]}]
  (let [arity (if (nil? args)
                (arg-count car)
                (- (arg-count car) (count args)))
        [stackargs stack] (if pop?
                            (popstack {:stack stack :count arity})
                            (getstack {:stack stack :count arity}))
        car (if (symbol? car) (eval car) car)
        car (if (var? car) @car car)
        meltargs (if (sequential? stackargs) stackargs [stackargs])
        meltargs (if (nil? args) meltargs
                     (if (sequential? args)
                       (reduce #(cons %2 %1) meltargs args)
                       (cons args meltargs)))]
    (cons (apply car meltargs) stack)))

(defn parse-flowcontrol-stmt "Parses one flowcontrol statement and applies it to the coll."
  [coll stmt]
  (cond
   (integer? stmt)
   (drop stmt coll)
   
   (keyword? stmt)
   (let [extract (->> coll (drop 1))]
     (cond
      (map? (first extract)) ((first extract) stmt)
      (coll? (first extract)) (drop 1 (drop-while #(not= stmt %) (first extract)))
      :else (drop 1 (drop-while #(not= stmt %) extract))))
   
   :else
   coll))

(defn parse-flowcontrol-coll "Coordinates parsing of a list of flowcontrol statements. Returns an actualized coll."
  [coll vargs]
  (if-not (sequential? vargs)
    (parse-flowcontrol-stmt coll vargs)
    (let [car (first vargs) cdr (rest vargs)]
      (cond
       (nil? car) coll
       (empty? cdr) (parse-flowcontrol-coll coll car)
       :else (parse-flowcontrol-coll
              (parse-flowcontrol-stmt coll car) cdr)))))


(defn eval-fork "Evaluates a fork expression. Returns [coll stack]."
  [{:keys [coll vargs stack]}]
  (let [fork (first coll)
        funstruct (eval (second fork))
        fun (if (fn? funstruct) funstruct (first funstruct))
        funargs (if (fn? funstruct) nil (rest funstruct))
        validarg (last fork)
        stack (eval-with-stack {:pop? (= :!fp (first fork)) :car fun :args funargs :stack stack})
        result (first stack)
        vargres (apply-validarg {:inp result :varg validarg :validargs vargs})]
    [(parse-flowcontrol-coll coll vargres) (conj stack result)]))

(defn parse-ret-cb "Parses a cb returned by a function."
  [{:keys [coll result]}]
  (if-not (and (sequential? (first result)))
    [(drop 1 coll) result]
    (if (contains-cb? (first result))
      (let [[coll stack] (parse-cb-stmt {:coll coll
                                         :cb (ffirst result)
                                         :stack result ;;(rest result)
                                         })
            stack (cons (if (>= 2 (count (first stack)))
                          (second (first stack))
                          (rest (first stack)))
                        (drop 1 stack))]
        [coll stack])
      [(drop 1 coll) result])))

(defn eval-elem "Evaluates the first element of the coll. Returns [coll stack]."
  [{:keys [coll vargs stack]}]
  (let [car (first coll)
        car (if-not (symbol? car) car @(resolve car))]
    (cond
     (or (and (sequential? car) (contains-cb? car))
        (and (keyword? car) (contains-cb? car)))
     (parse-cb-res {:coll coll :cbs car :stack stack})
     
     (or (keyword? car) (fn? car))
     (parse-ret-cb
      {:result (eval-with-stack {:car car :stack stack})
       :coll coll})
     
     (sequential? car)
     (if (or (= :!f (first car)) (= :!fp (first car)))
       (eval-fork {:coll coll :vargs vargs :stack stack})
       
       (let [pop? (= :!p (first car))
             cdr (if pop? (rest car) car)
             fun (first cdr)
             args (rest cdr)
             result (eval-with-stack {:pop? pop? :car fun :args args :stack stack})]
         (parse-ret-cb {:coll coll :result result})))
     
     :else ;; TODO broken?
     [(drop 1 coll) (conj stack car)])))

(defn walk "Walks its way through the list tree."
  ([{:keys [tree validargs stack]}]
     (walk true
           {:tree tree
            :validargs (if (nil? validargs)
                         builtin-validargs
                         (merge validargs builtin-validargs))
            :stack (if (list? stack) stack (list stack))}))
  ([r {:keys [tree validargs stack]}]
     (cond
      (list? (first tree))
      (walk true {:tree (first tree) :validargs validargs :stack stack})
      
      (empty? (rest tree))
      (if (empty? tree)
        (first stack)
        (-> (eval-elem {:coll tree :vargs validargs :stack stack}) second first))
      
      :else
      (let [[tree stack] (eval-elem {:coll tree :vargs validargs :stack stack})]
        (walk true {:tree tree :validargs validargs :stack stack}))
      )))

(def init-stack
  (let [lst '[fun1 fun2 fun3]]
    '({:index 0 :res nil} lst)))

(defn comp-validargs "Composes together validargs by chaining the results as core's comp does."
  [validargs]
  (let [wrap (fn wrap [{:keys [vargs res]}]
               (if (= 1 (count vargs))
                 {:vargs vargs :res res}
                 [:!d#0
                  {:vargs (rest vargs)
                   :res (cons (fn [x]
                                (v-is x (first vargs)))
                              res)}]))
        finalize (fn finalize [{:keys [vargs res]}]
                   (map
                    (fn [[cfun cres]]
                      [(comp cfun (first res)) cres])
                    (first vargs)))]
    (walk {:tree (list [:!p wrap] finalize)
           :stack {:res [] :vargs validargs}})))
