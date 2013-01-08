(ns visuflow.core)

(defn- arg-count [f]
  (let [f (if (fn? f) f @(resolve f))
        m (first (.getDeclaredMethods (class f)))
        p (.getParameterTypes m)]
    (alength p)))

(defn- v-data
  [arg]
  (if-not (or (nil? arg) (false? arg) (true? arg))
    1 2))

(defn- v-true
  [arg]
  (if (true? arg)
    1 2))

(defn- v-false
  [arg]
  (if (false? arg)
    1 2))

(defn- v-nil
  [arg]
  (if (nil? arg)
    1 2))

(defn- v-fnil
  [arg]
  (if (or (false? arg) (nil? arg))
    1 2))

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

(defn- popstack "Gets values from the stack by pop."
  [{:keys [stack count]}]
  [(take count stack) (drop count stack)])

(defn- getstack "Gets values from the stack by peek."
  [{:keys [stack count]}]
  [(take count stack) stack])

(defn- apply-validarg "Applies a validarg to a function result."
  [{:keys [inp varg validargs]}]
  (case varg
    :data (v-data inp)
    :true (v-true inp)
    :false (v-false inp)
    :nil (v-nil inp)
    :fnil (v-fnil inp)
    (if-let [is-varg (varg validargs)]
      (v-is inp is-varg)
      (throw (Exception. (str "Validarg does not exist: " varg " in " validargs))))))

(defn- contains-cb? "Checks if the given statement contains a cb (except :!f, :!p and :!fp)"
  [coll]
  (let [cb (if (sequential? coll) (name (first coll)) (name coll))]
    (if (and (.startsWith cb "!")
           (not= cb "!f") (not= cb "!p") (not= cb "!fp"))
      true false)))

(defn- parse-cb-stmt "Parses and applies one cb. Returns [coll stack]."
  [{:keys [coll cb stack]}]
  (let [cb (if (nil? cb) (first coll) cb)
        [com arg] (string/split (name cb) #"#")
        arg (when-not (nil? arg) (. Integer (parseInt arg)))]
    (case com
      "!_" [coll (take arg stack)]
      "!d" [(drop arg coll) stack]
      "!q" [nil (first stack)])))

(defn- parse-cb-res "Coordinating parsing of a list of validarg statements. Returns [coll stack]."
  [{:keys [coll cbs stack]}]
  (if-not (sequential? cbs)
    (parse-cb-stmt {:coll coll :cb cbs :stack stack})
    (let [car (first cbs) cdr (rest cbs)]
      (if (or (empty? cdr) (nil? cdr))
        (if (nil? car)
          [coll stack]
          (parse-cb-stmt {:coll coll :cb car :stack stack}))
        (let [[coll stack] (parse-cb-stmt {:coll coll :cb car :stack stack})]
          (parse-cb-res {:coll coll :cbs cdr :stack stack}))))))

(defn- eval-with-stack "Evaluates a function using values from the stack."
  [{:keys [pop? car args stack]}]
  (let [arity (if (nil? args)
                (arg-count car)
                (- (arg-count car) (count args)))
        [stackargs stack] (if pop?
                            (popstack {:stack stack :count arity})
                            (getstack {:stack stack :count arity}))
        result (if (nil? args)
                 (apply car stackargs)
                 (apply car (flatten (conj [] args stackargs))))]
    (conj stack result)))

(defn- parse-validarg-stmt "Parses one validarg statement and applies it to the coll."
  [coll stmt]
  (cond
   (integer? stmt)
   (drop stmt coll)
   
   (keyword? stmt)
   (->> coll (drop 1) stmt)
   
   :else
   coll))

(defn- parse-validarg-res "Coordinates parsing of a list of validarg statements. Returns an actualized coll."
  [coll vargs]
  (if-not (sequential? vargs)
    (parse-validarg-stmt coll vargs)
    (let [car (first vargs) cdr (rest vargs)]
      (if (or (empty? cdr) (nil? cdr))
        (if (nil? car)
          coll
          (parse-validarg-stmt coll car))
        (parse-validarg-res (parse-validarg-stmt coll car) cdr)))))

(defn- eval-fork "Evaluates a fork expression. Returns [coll stack]."
  [{:keys [coll vargs stack]}]
  (let [fork (first coll)
        funstruct (eval (second fork))
        fun (if (fn? funstruct) funstruct (first funstruct))
        funargs (if (fn? funstruct) nil (rest funstruct))
        validarg (last fork)
        stack (eval-with-stack {:pop? (= :!fp (first fork)) :car fun :args funargs :stack stack})
        result (first stack)
        vargres (apply-validarg {:inp result :varg validarg :validargs vargs})]
    [(parse-validarg-res coll vargres) (conj stack result)]))

(defn- eval-elem "Evaluates the first element of the coll. Returns [coll stack]."
  [{:keys [coll vargs stack]}]
  (let [car (first coll)
        car (if-not (symbol? car) car @(resolve car))
        arg (first stack)]
    (cond
     (or (and (sequential? car) (contains-cb? car))
        (and (keyword? car) (contains-cb? car)))
     (parse-cb-res {:coll coll :cbs car :stack stack})
     
     (or (keyword? car) (fn? car))
     [(drop 1 coll) (eval-with-stack {:car car :stack stack})]
     
     (sequential? car)
     (if (or (= :!f (first car)) (= :!fp (first car)))
       (eval-fork {:coll coll :vargs vargs :stack stack})
       
       (let [pop? (= :!p (first car))
             cdr (if pop? (rest car) car)
             fun (first cdr)
             args (rest cdr)
             result (eval-with-stack {:pop? pop? :car fun :args args :stack stack})]
         (if-not (and (sequential? result) (contains-cb? (first result)))
           [(drop 1 coll) result]
           (parse-cb-res {:coll coll
                          :cbs (first result)
                          :stack (if (> 1 (count (rest result)))
                                   (conj stack (rest result))
                                   (conj stack (second result)))}))))
     
     :else ;; TODO broken?
     [(drop 1 coll) (conj stack (arg car))])))

(defn walk "Walks its way through the list tree."
  ([x {:keys [tree validargs stack]}]
     (let [stack (if (list? stack) stack (list stack))]
      (cond
       (list? (first tree))
       (walk true {:tree (first tree) :validargs validargs :stack stack})
       
       (empty? (rest tree))
       (-> (eval-elem {:coll tree :vargs validargs :stack stack}) second first)
       
       :else
       (let [[tree stack] (eval-elem {:coll tree :vargs validargs :stack stack})]
         (walk true {:tree tree :validargs validargs :stack stack}))
       ))))