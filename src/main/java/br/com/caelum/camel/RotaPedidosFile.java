package br.com.caelum.camel;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.http4.HttpMethods;
import org.apache.camel.impl.DefaultCamelContext;

public class RotaPedidosFile {

	public static void main(String[] args) throws Exception {

		CamelContext context = new DefaultCamelContext();
		context.addRoutes(new RouteBuilder() {
			
			@Override
			public void configure() throws Exception {
				
				errorHandler(deadLetterChannel("file:erro") // Gera um arquivo de erro
					.logExhaustedMessageHistory(true) // Mostra o erro no console apenas uma vez
					.maximumRedeliveries(3) // Número de retentativas de entrega
					.redeliveryDelay(2000) // Tempo entre cada retentaiva de entrega
					.onRedelivery(new Processor() {						
						@Override
						public void process(Exchange exchange) throws Exception {
							int count = (int) exchange.getIn().getHeader(Exchange.REDELIVERY_COUNTER);
							int max = (int) exchange.getIn().getHeader(Exchange.REDELIVERY_MAX_COUNTER);
							System.out.println("Retentativa " + count + "/" + max);							
						}
					})
				);
				
				from("file:pedidos?delay=5&noop=true")
					.routeId("rota-pedidos")
					.to("validator:pedido.xsd") // Validação da entrada de dados
						.multicast() // Permite que a mesma mensagem seja enviada para vários endpoints.
							.to("direct:file")
							.to("direct:soap")
							.to("direct:http")
							.to("direct:mock");
				
				from("direct:file")
					.routeId("rota-file")
					.split()
						.xpath("/pedido/itens/item")
					.filter()
						.xpath("/item/formato[text() = 'EBOOK']")
					.marshal().xmljson()
					.log("${id} - ${body}")
						.setHeader(Exchange.FILE_NAME , simple("${file:name.noext}-${header.CamelSplitIndex}.json"))
				.to("file:saida");		
		
				from("direct:http")
					.routeId("rota-http")
					.setProperty("pedidoId", xpath("/pedido/id/text()") )
					.setProperty("clienteId", xpath("/pedido/pagamento/email-titular/text()") )
					.split()
						.xpath("/pedido/itens/item")
					.filter()
						.xpath("/item/formato[text() = 'EBOOK']")
						.setProperty("ebookId", xpath("/item/livro/codigo/text()") )
					.marshal().xmljson()
					.log("${id} - ${body}")
					.setHeader(Exchange.HTTP_METHOD , HttpMethods.GET)
					.setHeader(Exchange.HTTP_QUERY , simple("ebookId=${property.ebookId}&pedidoId=${property.pedidoId}&clienteId=${property.clienteId}"))
				.to("http4://localhost:8080/webservices/ebook/item");		
				
				from("direct:soap")
					.routeId("rota-soap")
					.to("xslt:pedido-para-soap.xslt") //Template XSLT
					.setHeader(Exchange.CONTENT_TYPE, constant("text/xml"))
				.to("http4://localhost:8080/webservices/financeiro"); //Componente para chamar recursos HTTP externos
				
				from("direct:mock")
					.routeId("rota-mock")
					.setBody(constant("<envelope>teste</envelope>"))
				.to("mock:soap"); //Chamada mock
			}
		});

		context.start();
		Thread.sleep(20000);
		context.stop();
	}	
}
