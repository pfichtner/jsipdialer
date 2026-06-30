FROM debian:bookworm-slim
RUN apt-get update -qq && apt-get install -y -qq kamailio && rm -rf /var/lib/apt/lists/*
COPY kamailio-test.cfg /etc/kamailio/kamailio.cfg
EXPOSE 15060/udp
CMD ["kamailio", "-f", "/etc/kamailio/kamailio.cfg", "-DD", "-E"]
