FROM golang:1.21-alpine AS builder
WORKDIR /app
ENV GOPROXY=https://goproxy.cn,direct
COPY go.mod go.sum ./
RUN go mod download
COPY main.go .
RUN CGO_ENABLED=0 go build -o /go-service main.go

FROM alpine:3.19
RUN sed -i 's/dl-cdn.alpinelinux.org/mirrors.aliyun.com/g' /etc/apk/repositories && apk add --no-cache ca-certificates
COPY --from=builder /go-service /go-service
EXPOSE 8080
ENTRYPOINT ["/go-service"]