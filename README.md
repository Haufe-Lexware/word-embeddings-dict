Word-Embeddings-Dict
====================

This is an akka-based microservice to act as dictionary for multilanguage word embeddings.

The service run in two modes:

*  embeddings conversion
*  dictionary

### Embeddings conversion
The first mode is activated by setting the environment variable ```DO_CONVERSION=true```.
It will convert embeddings from ```csv``` to ```parquet``` files. The service will then terminate.
All the embeddings must reside in ```/data/ft/``` (a mounted volume) and have filename of the form
```
wiki.<language>_en.vec
```
e.g.
```
wiki.fr_en.vec
```
except English which will be
```bash
wiki.en.vec
```

The reason for this names is that all vectors for languages other than English 
are aligned with the English ones.

### Dictionary
This is the main mode of execution. The service will provide 

*  vector dictionary for all supported languages
*  synonyms (k-nn)
*  analogies (king - man + woman ~= queen; also k-nn)

Check ```com.haufe.umantis.ds.embdict.messages``` for possible message queries.

Build
-----
```bash
sbt docker:publishLocal 
docker-compose build 
docker-compose up -d
```

Deployment
----------
This dictionary service needs access to word vectors in ```/data/ft``` through a mounted volume.
Ports ```5150``` and ```5151``` need to be exposed. 

Environment Variables (from docker-compose.yml)
-----------------------------------------------
    OPENBLAS_NUM_THREADS: 1
    LANGUAGES: ${LANGUAGES:-ar,bg,ca,cs,da,de,el,en,es,et,fi,fr,he,hr,hu,id,it,mk,nl,no,pl,pt,ro,ru,sk,sl,sv,tr,uk,vi}
    DICTIONARY_SIZE: ${DICTIONARY_SIZE:-all}
    DO_CONVERSION: ${DO_CONVERSION:-false}
    RESCALE_VECTORS: ${RESCALE_VECTORS:-true}
    DICT_TYPES: ${DICT_TYPES:-parallel}
    
Adding new messages
-------------------
This services uses ```akka-kryo-serialization``` to serialize messages instead of the classic Java
serializer for performance reasons. 
If new messages need to be added, they have to be explicitly listed in the configuration file.
Check ```src/main/resources/kryo_serializer.conf``` for further information.

Contacts
--------
This software is released under the terms of the GNU GPL 3 License.
It was developed by Nicola Bova at Haufe-Umantis, Barcelona, Spain.

Email: ```nicola dot bova at gmail dot com``` 