#!/usr/bin/env bash

cd ../../data/ft

for fname in `ls wiki.multi*`; do
        echo $fname;
        lang=`echo $fname| awk -F "." '{print $3}'`
        destfname="wiki.${lang}_en.vec"
        echo $fname -> $destfname
        mv $fname $destfname
done
mv wiki.en_en.vec wiki.en.vec