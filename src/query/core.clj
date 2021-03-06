(ns query.core
  (:use [clojure.pprint :only [pprint]])
  (:require [clojure.string :as string])
  (:require [instaparse.core :as insta])
  (:import com.thomasdimson.wikipedia.lda.java.DBAccess)
  (:import com.thomasdimson.wikipedia.Data$TSPRGraphNode)
  (:import com.thomasdimson.wikipedia.lda.java.SimilarityUtils)
  (:import com.thomasdimson.wikipedia.lda.java.TopicSensitivePageRank)
)

(set! *warn-on-reflection* true)

(require '[clojure.string :as string])
(require '[clojure.java.io :as io])


; Debug macros
(defmacro dbg-b [str & body] `(do (println ~str) ~@body))
(defmacro dbg-a [str & body] `(let [x# ~@body] (do (println ~str) x#)))
(defmacro dbg [& body] `(let [x# ~@body] (do (println x#) x#)))


(def question-parse
  (insta/parser
    "
      <S> = (similar_query|compare_query|topk_query) <query_endings>? 
      query_endings = sep* ('?'|'.'|'!')? sep*

      feature_specializer = <sep>? <'*'> <'*'> ('tspr' | 'lda' | 'lspr' | 'x2' | 'emass')
      norm_specializer = <sep>? <'*'> <'*'> ('cosine' | 'l2') 

      compare_query = <'compare' | 'Compare'> <sep> article_split (<sep> topic_refinement)?
      <article_split> = article_title <article_divider> article_title
      article_divider = sep ('and' | 'to' | 'with') sep
      
      similar_query = (<sim_question_phrase> <sep>)? (infobox <sep> (<'is' sep>)?)? <'similar'> (<sep> <'to'>)? <sep> article_title (feature_specializer | norm_specializer)*
      sim_question_phrase = question_word | question_word <sep> 'is'

      topk_query = (<topk_question_phase> <sep>)? (<topk_indicator> <sep>)? infobox (<sep> topic_refinement)? (feature_specializer)?
      topk_question_phase = question_word (<sep> ('is'|'are'))?
      topk_indicator = ('the' <sep>)? ('top'|'most' <sep> 'influential'|'best'|'greatest')

      topic_refinement = <'in'> <sep> topics | <'related' sep 'to' sep> topics 

      question_word = 'who'|'what'|'which'|'Who'|'What'|'Which'
      infobox = word |  <'\"'> word_nodblquote <'\"'> | <'\\''> word_nosglquote <'\\''>

      <topics> = topic (<sep>? <'/'> topic)*
      topic = word |  <'\"'> word_nodblquote <'\"'> | <'\\''> word_nosglquote <'\\''>
      article_title = !'to' word (<sep> word)* |  <'\"'> word_nodblquote <'\"'> | <'\\''> word_nosglquote <'\\''>

      <word> = #'\\w+'
      <word_nodblquote> = #'[^\"]+'
      <word_nosglquote> = #'[^\\']+'
      sep  = #'\\s+'
    ")
)

; Helpers 
(defn parse-elements [element tree]
  (filter #(and (vector? %) (= element (first %))) (tree-seq vector? seq tree)))
(defn lift-article-titles [tree] (map #(string/join " " (rest %)) (parse-elements :article_title tree)))
(defn lift-topics [tree] (map #(second %) (parse-elements :topic tree)))
(defn lift-infobox [tree] (map #(second %) (parse-elements :infobox tree)))
(defn lift-norm [tree] (if-let [norm-element (first (parse-elements :norm_specializer tree))] 
                         (keyword (second norm-element))
                         :l2))
(defn lift-features [tree] (dbg-b tree (if-let [feature-element (first (parse-elements :feature_specializer tree))] 
                         (keyword (second feature-element))
                         :tspr)))

; Query templates 
(defn- knn-query-template [sim-tree]
  (let [article-title (first (lift-article-titles sim-tree))
        infobox (first (lift-infobox sim-tree))]
    {
     :type :knn
     :infobox infobox
     :article-title article-title
     :features (lift-features sim-tree)
     :norm (lift-norm sim-tree)
    }
))

(defn compare-template [compare-tree]
  (let [[a1 a2] (into [] (lift-article-titles compare-tree))
        topics (into [] (lift-topics compare-tree))]
    {
     :type :compare
     :first-article-title a1
     :second-article-title a2
     :topics topics
    }
  ))

(defn topk-template [topk-tree]
  (let [infobox (first (lift-infobox topk-tree))
        topics (into [] (lift-topics topk-tree))]
    {
     :type :top_k
     :infobox infobox
     :topics topics
     :features (lift-features topk-tree)
    }
))

(defn extract-query-template [query]
  (let [parse-tree (dbg (question-parse query))]
    (if (insta/failure? parse-tree)
      {:type :failure 
       :explanation {:column (:column (insta/get-failure parse-tree))}}
      (case (first (first parse-tree))
        :similar_query (knn-query-template (first parse-tree))
        :compare_query (compare-template (first parse-tree))
        :topk_query (topk-template (first parse-tree))
        nil
      )
    )
  )
)


(defn closest-article ^Data$TSPRGraphNode [^DBAccess db title] (.findArticle db title))
(defn closest-infobox [^DBAccess db infobox] infobox)
(defn closest-topic [^DBAccess db topics] 
  (when topics (.determineTopicIndex db topics))
)

(defn nearest-neighbors-disk [source features norm limit]
    (SimilarityUtils/nearestNeighbors source 
        (TopicSensitivePageRank/newTSPRGraphNodeIterator "data/full/tspr_lspr.dat")
        (name features) (name norm) limit)
)

(defn graph-node-obj [^Data$TSPRGraphNode n]
  {
   :title (.getTitle n)
   :infobox (.getInfoboxType n)
   :lda (into [] (.getLdaList n))
   :tspr (into [] (.getTsprList n))
   :lspr (into [] (.getLsprList n))
  }
)

(defmulti perform-query :type)

(defmethod perform-query :knn [knn-template]
  (let [db (DBAccess.)
        limit 50
        _ (dbg knn-template)
        features (:features knn-template)
        norm (:norm knn-template)
        infobox (:infobox knn-template)
        source-article (dbg (closest-article db (:article-title knn-template)))
        knn (if infobox 
              (.nearestNeighborsWithInfobox db source-article infobox (name features) (name norm) limit)
              (nearest-neighbors-disk source-article features norm limit)
            )
        ]
    {
      :type :knn
      :source-article (graph-node-obj source-article)
      :norm norm
      :features features
      :neighbors (into [] (map graph-node-obj knn))
    }
))

(defmethod perform-query :top_k [topk-template]
  (let [db (DBAccess.)
        limit 100
        topic-index (closest-topic db (:topics topk-template))
        features (:features topk-template)
        ^String infobox (closest-infobox db (:infobox topk-template))
        topk (cond 
               (and infobox (= :tspr features))  (.topByTSPRWithInfobox db topic-index infobox limit)
               (= :tspr features) (.topByTSPR db topic-index limit)
               (and infobox (= :lda features))  (.topByLDAWithInfobox db topic-index infobox limit)
               (= :lda features) (.topByLDA db topic-index limit)
               (and infobox (= :lspr features))  (.topByLSPRWithInfobox db topic-index infobox limit)
               (= :lspr features) (.topByLSPR db topic-index limit)
               (and infobox (= :x2 features))  (.topByX2WithInfobox db topic-index infobox limit)
               (= :x2 features) (.topByX2 db topic-index limit)
               (and infobox (= :emass features))  (.topByExpectedMassWithInfobox db topic-index infobox limit)
               (= :emass features) (.topByExpectedMass db topic-index limit)
              )
        ]
    {
     :type :top_k
     :infobox infobox
     :features features
     :topic-index topic-index
     :topic-words (:topics topk-template)
     :articles (into [] (map graph-node-obj topk))
    }
  )
)

(defmethod perform-query :compare [compare-template]
  (let [db (DBAccess.)
        ^Data$TSPRGraphNode a1 (closest-article db (:first-article-title compare-template))
        ^Data$TSPRGraphNode a2 (closest-article db (:second-article-title compare-template))
        topic-index (closest-topic db (:topics compare-template))
        ]
    (when (and a1 a2)
      {
       :type :compare
       :first-article (graph-node-obj a1)
       :second-article (graph-node-obj a2)
       :topic-index topic-index
       :lda-cosine-similarity (SimilarityUtils/cosineLDA a1 a2)
       :lda-l2-similarity (/ 1.0 (SimilarityUtils/l2LDA a1 a2))
       :tspr-cosine-similarity (SimilarityUtils/cosineTSPR a1 a2)
       :tspr-l2-similarity (/ 1.0 (SimilarityUtils/l2TSPR a1 a2))
       :lspr-cosine-similarity (SimilarityUtils/cosineLSPR a1 a2)
       :lspr-l2-similarity (/ 1.0 (SimilarityUtils/l2LSPR a1 a2))
       :ratio (/ (.getTspr a1 topic-index) (.getTspr a2 topic-index))
      }
    )
  )
)

